package dev.deploy.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The analysis backend all three frontends sit on.
 *
 * <p>The actual parsing lives in the native library {@code libdeploynative.so};
 * this class shares validation and engine routing between frontends. File analysis
 * uses LIEF/Capstone, pseudocode uses a private Ghidra process, and assembly uses Keystone.
 */
public final class DeployCore {

    public static final String VERSION = "0.4.0";
    /** The native backend is now backed by the LIEF ELF parser. */
    public static final String BACKEND = "lief";

    /** File analysis operations; assembly and byte-buffer analysis have dedicated tools. */
    private static final String[] OPS = {
            "overview", "symbols", "relocations", "strings", "metadata", "resolve", "disasm", "decompile",
    };

    static {
        System.loadLibrary("deploynative");
    }

    /** Native seam: one op against one file, JSON in, JSON out. */
    private static native String nativeAnalyze(String op, String path, String argsJson);
    private static native String nativeDisassemble(String path, String name, String address,
            int bytes, int count, String mode);
    private static native String nativeDecompile(String path, String name, String address, String specs, int max);
    private static native String nativeAssemble(String arch, String source, String address);
    private static native String nativeDisassembleBytes(byte[] bytes, String arch, String address, int max);

    public String ping() {
        return "deploy " + VERSION + " (backend " + BACKEND + ")";
    }

    public JSONObject status() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name", "deploy");
        o.put("version", VERSION);
        o.put("backend", BACKEND);
        o.put("abi", android.os.Build.SUPPORTED_ABIS[0]);
        JSONArray ops = new JSONArray();
        for (String op : OPS) {
            ops.put(op);
        }
        o.put("ops", ops);
        o.put("disassembler", "capstone");
        o.put("decompile", new JSONObject().put("available", true).put("engine", "ghidra")
                .put("version", "11.3.2").put("architectures", new JSONArray().put("aarch64")));
        o.put("assembler", "keystone");
        o.put("bufferDisassembly", true);
        o.put("apiVersion", 2);
        o.put("runtimeInjection", false);
        o.put("note", "ELF/byte analysis and assembly generation. Runtime access is provided by PosEdMCP in a scoped app.");
        return o;
    }

    /**
     * One op against one file.
     *
     * @param path absolute path to a shared object on disk, typically /sdcard
     * @param op   one of {@link #OPS}
     * @param args op-specific arguments, may be empty
     */
    public JSONObject analyze(String path, String op, JSONObject args) throws JSONException {
        if (args == null) args = new JSONObject();
        if ("decompile".equals(op)) {
            return DecompilerClient.analyze(path, args);
        }
        if ("disasm".equals(op)) {
            String name = args.optString("name", "");
            String address = args.optString("address", "");
            if (name.isEmpty() == address.isEmpty())
                throw new IllegalArgumentException("disasm requires exactly one of name or address");
            if (!address.isEmpty() && !address.matches("0[xX][0-9a-fA-F]{1,16}"))
                throw new IllegalArgumentException("address must be an ELF virtual address in 0x... form");
            int max = boundedInt(args, "max", 64, 1, 512);
            int bytes = boundedInt(args, "bytes", 0, 1, 4096);
            String mode = args.optString("mode", "auto");
            if (!mode.equals("auto") && !mode.equals("arm") && !mode.equals("thumb"))
                throw new IllegalArgumentException("mode must be auto, arm or thumb");
            return new JSONObject(nativeDisassemble(path, name, address, bytes, max, mode));
        }
        return new JSONObject(nativeAnalyze(op, path, args == null ? "{}" : args.toString()));
    }

    static JSONObject decompileLocal(String path, JSONObject args) throws Exception {
        String name = args.optString("name", ""), address = args.optString("address", "");
        if (name.isEmpty() == address.isEmpty()) throw new IllegalArgumentException("decompile requires exactly one of name or address");
        if (!address.isEmpty()) requireAddress(address);
        int max = boundedInt(args, "max", 4096, 1, 20000);
        return new JSONObject(nativeDecompile(path, name, address, GhidraSpecs.directory(), max));
    }

    public JSONObject assemble(String arch, String source, String address) throws JSONException {
        requireArch(arch); requireAddress(address);
        if (source == null || source.trim().isEmpty() || source.length() > 16384 || source.indexOf('\0') >= 0)
            throw new IllegalArgumentException("source must contain 1..16384 characters and no NUL");
        // Directives can allocate unbounded buffers (.space/.rept); this tool accepts instructions and labels only.
        if (source.contains("/*") || source.contains("*/") || source.indexOf('\\') >= 0
                || source.indexOf('"') >= 0 || source.indexOf('\'') >= 0)
            throw new IllegalArgumentException("block comments, escapes and quoted strings are not supported");
        for (char c : source.toCharArray())
            if (Character.isISOControl(c) && c != '\t' && c != '\r' && c != '\n')
                throw new IllegalArgumentException("unsupported control character");
        String[] statements = source.replaceAll("//[^\\r\\n]*", "").split("[;\\r\\n]");
        if (statements.length > 512) throw new IllegalArgumentException("at most 512 statements");
        for (String statement : statements) {
            String code = statement.trim().replaceFirst("^(?:[A-Za-z_][A-Za-z0-9_]*:\\s*)+", "");
            if (!code.isEmpty() && !code.matches("[A-Za-z][A-Za-z0-9_.]*(?:\\s+.*)?"))
                throw new IllegalArgumentException("only instructions and simple named labels are supported; no directives");
        }
        return new JSONObject(nativeAssemble(arch, source, address));
    }

    public JSONObject disassembleBytes(byte[] bytes, String arch, String address, JSONObject args) throws JSONException {
        requireArch(arch); requireAddress(address);
        if (bytes == null || bytes.length == 0 || bytes.length > 4096)
            throw new IllegalArgumentException("buffer must contain 1..4096 bytes");
        return new JSONObject(nativeDisassembleBytes(bytes, arch, address, boundedInt(args, "max", 64, 1, 512)));
    }

    public static byte[] decodeHex(String hex) {
        if (hex == null || !hex.matches("(?:[0-9a-fA-F]{2}){1,4096}"))
            throw new IllegalArgumentException("bytes must contain 1..4096 bytes as hexadecimal pairs");
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    private static void requireAddress(String address) {
        if (address == null || !address.matches("0[xX][0-9a-fA-F]{1,16}"))
            throw new IllegalArgumentException("address must be a hexadecimal 0x... string");
    }

    private static void requireArch(String arch) {
        if (!java.util.Arrays.asList("aarch64", "arm", "thumb", "x86", "x86_64").contains(arch))
            throw new IllegalArgumentException("unsupported architecture");
    }

    private static int boundedInt(JSONObject args, String key, int fallback, int min, int max)
            throws JSONException {
        if (!args.has(key)) return fallback;
        Object raw = args.get(key);
        if (!(raw instanceof Integer) && !(raw instanceof Long))
            throw new IllegalArgumentException(key + " must be an integer");
        long value = ((Number) raw).longValue();
        if (value < min || value > max)
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        return (int) value;
    }
}
