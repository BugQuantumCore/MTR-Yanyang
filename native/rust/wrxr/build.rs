// SPDX-License-Identifier: MIT
//
// build.rs — compile the C++ domain bundle for the Rust test binaries.
//
// `cargo test` links this crate plus the C++ half of the server
// (wrcore_domain.cpp + wrobj) so the protocol tests exercise the exact
// production composition: the Rust transport driving the C++ domain.
// The final shared library is linked by CMake instead, and CMake sets
// WRXR_SKIP_CPP_BUNDLE=1 so this step is skipped there (and on any machine
// without a C++ compiler in PATH, e.g. an MSVC runner building only the
// staticlib).

use std::env;
use std::path::PathBuf;
use std::process::Command;

fn main() {
    if env::var_os("WRXR_SKIP_CPP_BUNDLE").is_some() {
        return;
   }
    if std::env::var("CARGO_CFG_TARGET_ENV").as_deref() == Ok("msvc") {
        // The MSVC CI path exercises the C++ suite through CMake; the Rust
        // tests run on Linux.
        return;
    }

    let manifest = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    let native = manifest.parent().and_then(|p| p.parent()).unwrap().to_path_buf();
    let out = PathBuf::from(env::var("OUT_DIR").unwrap());

    // The full production composition minus the final link: wrmain brings
    // the platform ctor + the host-init trampoline, wrvk the recorder (the
    // Rust tests never attach one, but the symbols must resolve).
    let sources = [
        "src/wrcore_domain.cpp",
        "src/wrobj.cpp",
        "src/wrobj_parse.cpp",
        "src/wrobj_mesh.cpp",
        "src/wrobj_pipeline.cpp",
        "src/wrvk.cpp",
        "src/wrvk_loader.cpp",
        "src/wrvk_stub.cpp",
        "src/wrmain.cpp",
    ];

    // The bundle depends on the headers as much as the sources, and cargo
    // does not rerun this script for its own edits in every setup — be
    // explicit about all inputs.
    println!("cargo:rerun-if-changed=build.rs");
    let watched = [
        "src/wrcore_domain.cpp",
        "include/wrcore/wrcore.h",
        "include/wrx/wrx.hpp",
        "include/wrvk/wrvk.hpp",
        "include/wrobj/wrobj.hpp",
        "include/wrobj/wrobj_math.hpp",
        "include/wrobj/wrobj_model.hpp",
    ];
    for file in watched {
        println!("cargo:rerun-if-changed={}", native.join(file).display());
    }
    for source in sources {
        println!("cargo:rerun-if-changed={}", native.join(source).display());
    }

    let cxx = env::var("CXX").unwrap_or_else(|_| {
        for candidate in ["g++", "c++", "clang++"] {
            if Command::new(candidate)
                .arg("--version")
                .output()
                .map(|o| o.status.success())
                .unwrap_or(false)
            {
                return candidate.to_string();
            }
        }
        // No compiler: skip silently (CMake paths build the bundle there).
        eprintln!("wrxr: no C++ compiler found; skipping the domain bundle");
        std::process::exit(0);
    });

    let mut objects = Vec::new();
    for source in sources {
        let object = out.join(format!("{}.o", PathBuf::from(source).file_stem().unwrap().to_string_lossy()));
        let status = Command::new(&cxx)
            .args(["-std=c++17", "-O2", "-fPIC", "-w"])
            .arg(format!("-I{}", native.join("include").display()))
            .arg("-c")
            .arg(native.join(source))
            .arg("-o")
            .arg(&object)
            .status()
            .expect("failed to spawn the C++ compiler");
        assert!(status.success(), "failed to compile {}", source);
        objects.push(object);
    }

    let archive = out.join("libwrcore_bundle.a");
    let _ = std::fs::remove_file(&archive);
    let mut ar = Command::new("ar");
    ar.arg("crs").arg(&archive);
    for object in &objects {
        ar.arg(object);
    }
    let status = ar.status().expect("failed to spawn ar");
    assert!(status.success(), "failed to archive the domain bundle");

    println!("cargo:rustc-link-search=native={}", out.display());
    println!("cargo:rustc-link-lib=static=wrcore_bundle");
    match std::env::var("CARGO_CFG_TARGET_OS").as_deref() {
        Ok("macos") => println!("cargo:rustc-link-lib=dylib=c++"),
        Ok("windows") => {
            println!("cargo:rustc-link-lib=dylib=user32");
            println!("cargo:rustc-link-lib=dylib=gdi32");
        }
        _ => {
            println!("cargo:rustc-link-lib=dylib=stdc++");
            println!("cargo:rustc-link-lib=dylib=m");
            println!("cargo:rustc-link-lib=dylib=pthread");
            println!("cargo:rustc-link-lib=dylib=dl");
        }
    }
}
