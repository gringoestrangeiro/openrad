# Translation catalog

`messages.json` is embedded in both binaries. Each English source message is a key; its three translations are ordered **Portuguese (`pt`), Russian (`ru`), Vietnamese (`vi`)**. English uses the key itself. No language files are needed beside the executables.

Keep translations complete and use the same named or numbered placeholders as the English key. Format specifiers from Rust are removed in the catalog (`{code:#x}` becomes `{code}`, and `{}` becomes `{0}`, `{1}`, etc.). Parameters may be reordered. They are substituted once, so braces in names and paths are preserved literally.

UI labels use `Language::text`, and explicit formatted text uses `Language::format`. Canonical messages from the shared backend are translated with `Language::message` only at the presentation boundary. This allows retained activity, errors, and peer details to change language immediately while diagnostics and JSON stay stable. Unknown external-library/OS error details are preserved, and common OS errors have translations with their original error codes.

Run `cargo test --workspace --locked` after any catalog edit. The checks validate every translation and placeholder, audit application strings and shared errors, exercise locale precedence and CLI output, verify the embedded font's character map, and click translated network-dialog actions. See [desktop usage](../docs/desktop.md) for automatic locale detection and saved preferences.
