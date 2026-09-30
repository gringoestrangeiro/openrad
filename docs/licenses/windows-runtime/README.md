# Cross-build runtime notices

The MinGW-w64 runtime notice is retained verbatim from the Linux build host's
`mingw-w64-crt` package. It includes the notices for runtime code linked into
Windows binaries. GCC's Runtime Library Exception and GPL-3.0 text are also
retained. These are notices; no compiler or Linux runtime is installed on Windows.

The Windows installer packager additionally includes the standard-library
copyright/license document from the exact Rust toolchain used for the build,
alongside Cargo dependency notices. Install Rust's `rust-docs` component if that
document is missing from a minimal build toolchain.
