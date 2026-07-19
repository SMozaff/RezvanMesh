#!/usr/bin/env python3
"""
Rezvan Mesh - Interface Verification Script
Verifies that JNI exports in Rust match Kotlin external declarations.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).parent.parent

def parse_rust_jni_functions() -> set:
    rust_lib = ROOT / "rust/rezvan-core/src/lib.rs"
    if not rust_lib.exists():
        print(f"Error: {rust_lib} not found")
        return set()
    
    content = rust_lib.read_text()
    pattern = r'pub extern "C" fn (Java_com_rezvani_mesh_MeshCore_\w+)\('
    return set(re.findall(pattern, content))

def parse_kotlin_externals() -> set:
    kotlin_file = ROOT / "android/app/src/main/java/com/rezvani/mesh/MeshCore.kt"
    if not kotlin_file.exists():
        print(f"Error: {kotlin_file} not found")
        return set()
    
    content = kotlin_file.read_text()
    pattern = r'external fun (\w+)\('
    externals = set(re.findall(pattern, content))
    return {f"Java_com_rezvani_mesh_MeshCore_{name}" for name in externals}

def main():
    rust_funcs = parse_rust_jni_functions()
    kotlin_funcs = parse_kotlin_externals()
    
    missing_in_rust = kotlin_funcs - rust_funcs
    missing_in_kotlin = rust_funcs - kotlin_funcs
    
    if missing_in_rust:
        print("ERROR: Kotlin external functions not found in Rust:")
        for f in sorted(missing_in_rust):
            print(f"  - {f}")
    if missing_in_kotlin:
        print("ERROR: Rust JNI functions not declared in Kotlin:")
        for f in sorted(missing_in_kotlin):
            print(f"  - {f}")
    
    if missing_in_rust or missing_in_kotlin:
        sys.exit(1)
    
    print("Interface verification passed.")
    print(f"  Rust functions: {len(rust_funcs)}")
    print(f"  Kotlin externals: {len(kotlin_funcs)}")
    sys.exit(0)

if __name__ == "__main__":
    main()
