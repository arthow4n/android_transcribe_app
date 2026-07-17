use std::env;
use std::fs;
use std::path::PathBuf;

fn main() {
    // transcribe-cpp-sys reconstructs its link line from a generic Unix
    // manifest that lists `pthread` and the C++ runtime. Bionic has neither a
    // separate libpthread (pthreads live in libc) nor a full libstdc++ (the
    // real C++ runtime is libc++_shared, which the app already bundles), so
    // satisfy the former with an empty archive and link the latter explicitly.
    let target_os = env::var("CARGO_CFG_TARGET_OS").unwrap_or_default();
    if target_os == "android" {
        let out = PathBuf::from(env::var("OUT_DIR").unwrap());
        fs::write(out.join("libpthread.a"), b"!<arch>\n").unwrap();
        println!("cargo:rustc-link-search=native={}", out.display());
        println!("cargo:rustc-link-lib=dylib=c++_shared");
    }
}
