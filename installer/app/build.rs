fn main() {
    // Зашитые файлы: пересобрать, если сменились.
    println!("cargo:rerun-if-env-changed=OSMOTR_IPA");
    println!("cargo:rerun-if-env-changed=IPATOOL_EXE");
    tauri_build::build()
}
