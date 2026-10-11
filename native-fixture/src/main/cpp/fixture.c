#include <stdint.h>
#include <unistd.h>

static int64_t constructor_pid;

__attribute__((constructor)) static void capture_process(void) {
    constructor_pid = getpid();
}

// The entry ABI promised by deploy_load_native: int64_t function(void).
__attribute__((visibility("default"))) int64_t deploy_fixture_probe(void) {
    return constructor_pid;
}

__attribute__((visibility("default"))) int64_t deploy_fixture_answer(void) {
    return 42;
}

__attribute__((visibility("default"), noinline)) int64_t deploy_fixture_calculate(int64_t x) {
    if (x < 0) return -x;
    return x * 3 + 7;
}
