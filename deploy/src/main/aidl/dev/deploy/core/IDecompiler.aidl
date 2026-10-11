package dev.deploy.core;
interface IDecompiler {
    int processId();
    String decompile(String path, String argsJson);
}
