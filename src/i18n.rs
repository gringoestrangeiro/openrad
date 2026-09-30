//! Presentation-only localization. Protocol values, diagnostics and JSON stay stable.
//!
//! English is the catalog key, so unknown external/OS errors remain readable.
//! Catalog templates are matched as whole messages; user-provided names and paths
//! are opaque parameters and are never translated or interpreted as templates.
use serde::{Deserialize, Serialize};
use std::{collections::BTreeMap, ffi::OsString, str::FromStr, sync::OnceLock};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum Language {
    #[serde(rename = "en")]
    English,
    #[serde(rename = "pt")]
    Portuguese,
    #[serde(rename = "ru")]
    Russian,
    #[serde(rename = "vi")]
    Vietnamese,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub enum LanguagePreference {
    #[default]
    #[serde(rename = "system")]
    System,
    #[serde(rename = "en")]
    English,
    #[serde(rename = "pt")]
    Portuguese,
    #[serde(rename = "ru")]
    Russian,
    #[serde(rename = "vi")]
    Vietnamese,
}

impl LanguagePreference {
    pub const ALL: [Self; 5] = [
        Self::System,
        Self::English,
        Self::Portuguese,
        Self::Russian,
        Self::Vietnamese,
    ];

    pub fn resolve(self, system: Language) -> Language {
        match self {
            Self::System => system,
            Self::English => Language::English,
            Self::Portuguese => Language::Portuguese,
            Self::Russian => Language::Russian,
            Self::Vietnamese => Language::Vietnamese,
        }
    }

    /// Autonyms keep the selector recognizable after an accidental language change.
    pub fn label(self, current: Language) -> &'static str {
        match self {
            Self::System => current.text("System language (automatic)"),
            Self::English => Language::English.name(),
            Self::Portuguese => Language::Portuguese.name(),
            Self::Russian => Language::Russian.name(),
            Self::Vietnamese => Language::Vietnamese.name(),
        }
    }
}

impl FromStr for LanguagePreference {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        if value.eq_ignore_ascii_case("system") {
            return Ok(Self::System);
        }
        match Language::from_locale(value) {
            Some(Language::English) => Ok(Self::English),
            Some(Language::Portuguese) => Ok(Self::Portuguese),
            Some(Language::Russian) => Ok(Self::Russian),
            Some(Language::Vietnamese) => Ok(Self::Vietnamese),
            None => Err(format!(
                "Unsupported language: {value}. Use system, en, pt, ru or vi."
            )),
        }
    }
}

impl std::fmt::Display for LanguagePreference {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::System => "system",
            Self::English => "en",
            Self::Portuguese => "pt",
            Self::Russian => "ru",
            Self::Vietnamese => "vi",
        })
    }
}

struct Catalog {
    entries: BTreeMap<String, [String; 3]>,
    templates: Vec<String>,
}

fn catalog() -> &'static Catalog {
    static CATALOG: OnceLock<Catalog> = OnceLock::new();
    CATALOG.get_or_init(|| {
        let entries: BTreeMap<String, [String; 3]> =
            serde_json::from_str(include_str!("../locales/messages.json"))
                .expect("validated embedded translation catalog");
        let mut templates: Vec<_> = entries
            .keys()
            .filter(|key| key.contains('{'))
            .cloned()
            .collect();
        // Prefer specific templates over generic wrappers, such as "{0} completed".
        templates.sort_by_key(|key| std::cmp::Reverse(literal_length(key)));
        Catalog { entries, templates }
    })
}

impl Language {
    pub const ALL: [Self; 4] = [
        Self::English,
        Self::Portuguese,
        Self::Russian,
        Self::Vietnamese,
    ];

    pub fn name(self) -> &'static str {
        match self {
            Self::English => "English",
            Self::Portuguese => "Português",
            Self::Russian => "Русский",
            Self::Vietnamese => "Tiếng Việt",
        }
    }

    pub fn localized_name(self, language: Language) -> &'static str {
        language.text(match self {
            Self::English => "English",
            Self::Portuguese => "Portuguese",
            Self::Russian => "Russian",
            Self::Vietnamese => "Vietnamese",
        })
    }

    pub fn from_locale(locale: &str) -> Option<Self> {
        let code = locale.trim().split(['_', '-', '.', '@']).next()?;
        if code.eq_ignore_ascii_case("en") {
            Some(Self::English)
        } else if code.eq_ignore_ascii_case("pt") {
            Some(Self::Portuguese)
        } else if code.eq_ignore_ascii_case("ru") {
            Some(Self::Russian)
        } else if code.eq_ignore_ascii_case("vi") {
            Some(Self::Vietnamese)
        } else {
            None
        }
    }

    pub fn system() -> Self {
        #[cfg(windows)]
        if !["LC_ALL", "LC_MESSAGES", "LANG", "LANGUAGE"]
            .iter()
            .any(|key| std::env::var(key).is_ok_and(|value| !value.trim().is_empty()))
        {
            let mut locale = [0u16; 85];
            // SAFETY: writable buffer with the documented LOCALE_NAME_MAX_LENGTH.
            let len = unsafe {
                windows_sys::Win32::Globalization::GetUserDefaultLocaleName(
                    locale.as_mut_ptr(),
                    locale.len() as i32,
                )
            };
            if len > 1 {
                return Self::from_locale(&String::from_utf16_lossy(&locale[..len as usize - 1]))
                    .unwrap_or(Self::English);
            }
        }
        detect_language(|key| std::env::var(key).ok())
    }

    pub fn lookup(self, source: &str) -> Option<&'static str> {
        let (key, translations) = catalog().entries.get_key_value(source)?;
        Some(match self {
            Self::English => key.as_str(),
            Self::Portuguese => &translations[0],
            Self::Russian => &translations[1],
            Self::Vietnamese => &translations[2],
        })
    }

    pub fn text(self, source: &str) -> &str {
        self.lookup(source).unwrap_or(source)
    }

    pub fn format(self, source: &str, parameters: &[(&str, &str)]) -> String {
        substitute(self.text(source), parameters)
    }

    /// Localize canonical backend messages at display time, including retained
    /// activity and errors, so switching languages also updates existing notices.
    pub fn message(self, source: &str) -> String {
        self.message_depth(source, 0)
    }

    fn message_depth(self, source: &str, depth: usize) -> String {
        if self == Self::English || depth >= 12 {
            return source.to_owned();
        }
        if let Some(text) = self.lookup(source) {
            return text.to_owned();
        }
        let suffix = " Reconnecting with the same identity…";
        if let Some(cause) = source.strip_suffix(suffix) {
            return format!(
                "{}{}",
                self.message_depth(cause, depth + 1),
                self.text(suffix)
            );
        }
        for template in &catalog().templates {
            if let Some(parameters) = match_template(template, source) {
                if matches!(template.as_str(), "{0} networks" | "{0} peers")
                    && parameters
                        .first()
                        .is_none_or(|(_, value)| value.parse::<u64>().is_err())
                {
                    continue;
                }
                let values: Vec<_> = parameters
                    .iter()
                    .map(|(key, value)| {
                        let nested = matches!(*key, "e" | "error" | "detail" | "hint" | "suffix")
                            || (template == "Authenticated {0} · {1}")
                            || (template == "{0} completed")
                            || (template == "direct TCP candidates failed: {0}")
                            || (template == "invalid candidate: {0}")
                            || (template
                                == "{0} · Retry queued (at least {1}s; adaptive recovery)"
                                && *key == "0");
                        if nested {
                            self.message_depth(value, depth + 1)
                        } else {
                            (*value).to_owned()
                        }
                    })
                    .collect();
                let parameters: Vec<_> = parameters
                    .iter()
                    .zip(&values)
                    .map(|((key, _), value)| (*key, value.as_str()))
                    .collect();
                return self.format(template, &parameters);
            }
        }
        // anyhow error chains and candidate error lists. Only split a known
        // context; unknown operating-system details are preserved verbatim.
        for separator in [": ", "; "] {
            if let Some((context, cause)) = source.split_once(separator) {
                if let Some(context) = self.lookup(context) {
                    return format!(
                        "{context}{separator}{}",
                        self.message_depth(cause, depth + 1)
                    );
                }
                if separator == ": " && context.parse::<std::net::SocketAddr>().is_ok() {
                    return format!("{context}: {}", self.message_depth(cause, depth + 1));
                }
            }
        }
        source.to_owned()
    }
}

/// GNU message-locale precedence, without mutating process-global locale state.
/// C/POSIX explicitly requests English and disables LANGUAGE preference lists.
fn detect_language(mut env: impl FnMut(&str) -> Option<String>) -> Language {
    let locale = ["LC_ALL", "LC_MESSAGES", "LANG"]
        .into_iter()
        .filter_map(&mut env)
        .find(|value| !value.trim().is_empty());
    if locale.as_deref().is_some_and(|locale| {
        let language = locale.split(['.', '@']).next().unwrap_or(locale);
        language.eq_ignore_ascii_case("C") || language.eq_ignore_ascii_case("POSIX")
    }) {
        return Language::English;
    }
    if let Some(list) = env("LANGUAGE") {
        for locale in list.split(':') {
            if let Some(language) = Language::from_locale(locale) {
                return language;
            }
        }
    }
    locale
        .as_deref()
        .and_then(Language::from_locale)
        .unwrap_or(Language::English)
}

/// Replace placeholders in one pass: parameter values can contain braces or
/// another placeholder's name, and must never be substituted a second time.
fn substitute(template: &str, parameters: &[(&str, &str)]) -> String {
    let mut remaining = template;
    let mut result = String::with_capacity(template.len());
    while let Some(start) = remaining.find('{') {
        let Some(end) = remaining[start..].find('}').map(|n| start + n) else {
            break;
        };
        result.push_str(&remaining[..start]);
        let key = &remaining[start + 1..end];
        match parameters.iter().find(|(name, _)| *name == key) {
            Some((_, value)) => result.push_str(value),
            None => result.push_str(&remaining[start..=end]),
        }
        remaining = &remaining[end + 1..];
    }
    result.push_str(remaining);
    result
}

fn literal_length(template: &str) -> usize {
    let mut literal = 0;
    let mut parameter = false;
    for character in template.chars() {
        match character {
            '{' => parameter = true,
            '}' => parameter = false,
            _ if !parameter => literal += 1,
            _ => {}
        }
    }
    literal
}

fn match_template<'a, 'b>(template: &'a str, message: &'b str) -> Option<Vec<(&'a str, &'b str)>> {
    let mut parameters = Vec::new();
    let mut template = template;
    let mut remaining = message;
    while let Some(start) = template.find('{') {
        remaining = remaining.strip_prefix(&template[..start])?;
        let end = template[start..].find('}')? + start;
        let key = &template[start + 1..end];
        template = &template[end + 1..];
        let literal = template.split('{').next()?;
        let split = if template.is_empty() {
            remaining.len()
        } else if literal.is_empty() {
            return None; // Adjacent parameters require an explicit format call.
        } else if !template.contains('{') {
            // Error details and network names may contain the same delimiter.
            remaining.rfind(literal)?
        } else {
            remaining.find(literal)?
        };
        parameters.push((key, &remaining[..split]));
        remaining = &remaining[split..];
    }
    (remaining == template).then_some(parameters)
}

/// Translate clap's descriptions and headings without changing command syntax.
pub fn localize_command(mut command: clap::Command, language: Language) -> clap::Command {
    command.build();
    if let Some(about) = command
        .get_about()
        .and_then(|s| language.lookup(&s.to_string()))
    {
        command = command.about(about);
    }
    command
        .help_template(
            language.text("{about-with-newline}\nUsage: {usage}\n\n{all-args}{after-help}"),
        )
        .subcommand_help_heading(language.text("Commands"))
        .mut_args(|mut arg| {
            if let Some(help) = arg.get_help().and_then(|s| language.lookup(&s.to_string())) {
                arg = arg.help(help);
            }
            if let Some(help) = arg
                .get_long_help()
                .and_then(|s| language.lookup(&s.to_string()))
            {
                arg = arg.long_help(help);
            }
            let heading = if arg.is_positional() {
                "Arguments"
            } else {
                "Options"
            };
            arg.help_heading(language.text(heading))
        })
        .mut_subcommands(|subcommand| localize_command(subcommand, language))
}

pub fn language_for_args(args: &[OsString], default: LanguagePreference) -> Language {
    let mut preference = default;
    let mut args = args.iter().skip(1).take_while(|arg| *arg != "--");
    while let Some(arg) = args.next() {
        let value = if arg == "--language" {
            args.next().and_then(|value| value.to_str())
        } else {
            arg.to_str()
                .and_then(|value| value.strip_prefix("--language="))
        };
        if let Some(value) = value.and_then(|value| value.parse().ok()) {
            preference = value;
        }
    }
    preference.resolve(Language::system())
}

/// clap's built-in formatter has fixed English headings and annotations. Limit
/// their translation to generated lines; argument values remain opaque.
pub fn localize_cli_output(output: &str, language: Language) -> String {
    let mut result = String::new();
    for line in output.split_inclusive('\n') {
        let content = line.trim_start();
        let indent = &line[..line.len() - content.len()];
        let content = content.trim_end_matches('\n');
        let mut localized = if content.starts_with("error: ")
            || content.starts_with("tip: ")
            || content.starts_with("Usage: ")
            || content == "For more information, try '--help'."
        {
            language.message(content)
        } else {
            content.to_owned()
        };
        for heading in ["default", "possible values", "aliases"] {
            let prefix = format!("[{heading}: ");
            if let Some(start) = localized.find(&prefix) {
                if let Some(end) = localized[start..].find(']').map(|end| start + end) {
                    let source = &localized[start..=end];
                    let translated = language.message(source);
                    localized.replace_range(start..=end, &translated);
                }
            }
        }
        result.push_str(indent);
        result.push_str(&localized);
        if line.ends_with('\n') {
            result.push('\n');
        }
    }
    result
}

pub fn parse_localized<T: clap::Parser>(default: LanguagePreference) -> (T, Language) {
    let args: Vec<_> = std::env::args_os().collect();
    let language = language_for_args(&args, default);
    let mut command = localize_command(T::command(), language);
    let result = command.try_get_matches_from_mut(args).and_then(|matches| {
        T::from_arg_matches(&matches).map_err(|error| error.format(&mut command))
    });
    match result {
        Ok(parsed) => (parsed, language),
        Err(error) => {
            let output = localize_cli_output(&error.to_string(), language);
            if error.use_stderr() {
                eprint!("{output}");
            } else {
                print!("{output}");
            }
            std::process::exit(error.exit_code());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn detect(values: &[(&str, &str)]) -> Language {
        detect_language(|key| {
            values
                .iter()
                .find(|(name, _)| *name == key)
                .map(|(_, v)| v.to_string())
        })
    }

    #[test]
    fn system_language_honors_locale_precedence_and_preference_lists() {
        assert_eq!(detect(&[]), Language::English);
        for (locale, language) in [
            ("pt_BR.UTF-8", Language::Portuguese),
            ("pt-PT", Language::Portuguese),
            ("RU_ru.utf8@modifier", Language::Russian),
            ("vi-VN", Language::Vietnamese),
            ("en_GB", Language::English),
            ("fr_FR.UTF-8", Language::English),
        ] {
            assert_eq!(detect(&[("LANG", locale)]), language);
        }
        assert_eq!(
            detect(&[
                ("LC_ALL", "ru_RU"),
                ("LC_MESSAGES", "vi_VN"),
                ("LANG", "pt_BR")
            ]),
            Language::Russian
        );
        assert_eq!(
            detect(&[("LC_ALL", ""), ("LC_MESSAGES", "vi_VN"), ("LANG", "pt_BR")]),
            Language::Vietnamese
        );
        assert_eq!(
            detect(&[("LANG", "pt_BR"), ("LANGUAGE", "fr:vi:ru")]),
            Language::Vietnamese
        );
        for locale in ["C", "C.UTF-8", "POSIX"] {
            assert_eq!(
                detect(&[("LC_ALL", locale), ("LANGUAGE", "ru:pt")]),
                Language::English
            );
        }
        assert_eq!(
            LanguagePreference::System.resolve(Language::Vietnamese),
            Language::Vietnamese
        );
        assert_eq!(
            LanguagePreference::Russian.resolve(Language::Portuguese),
            Language::Russian
        );
    }

    fn placeholders(text: &str) -> Vec<&str> {
        let mut remaining = text;
        let mut keys = Vec::new();
        while let Some(start) = remaining.find('{') {
            let end = remaining[start..].find('}').expect("balanced placeholder") + start;
            let key = &remaining[start + 1..end];
            assert!(!key.is_empty(), "named or numbered placeholder required");
            keys.push(key);
            remaining = &remaining[end + 1..];
        }
        assert!(!remaining.contains('}'), "balanced placeholder");
        keys.sort_unstable();
        keys
    }

    #[test]
    fn every_translation_is_present_and_preserves_all_parameters() {
        assert!(catalog().entries.len() > 200);
        for (source, translations) in &catalog().entries {
            assert!(!source.trim().is_empty());
            for translation in translations {
                assert!(
                    !translation.trim().is_empty(),
                    "missing translation: {source}"
                );
                assert_eq!(
                    placeholders(source),
                    placeholders(translation),
                    "parameters: {source}"
                );
                assert!(!translation.contains('�'), "invalid Unicode: {source}");
            }
        }
    }

    #[test]
    fn localized_messages_preserve_names_paths_codes_and_literal_braces() {
        for language in Language::ALL {
            let name = "Connected {e} · Tiếng Việt / Русский";
            assert_eq!(
                language.message(&format!("Created {name}")),
                language.format("Created {name}", &[("name", name)])
            );
            assert_eq!(
                language.message("an unknown OS error /home/Connected/{e}"),
                "an unknown OS error /home/Connected/{e}"
            );
            assert_eq!(
                language.message("Joined {name}"),
                language.format("Joined {0}", &[("0", "{name}")])
            );
            assert_eq!(
                language.format(
                    "{first} {second}",
                    &[("first", "{second}"), ("second", "value")]
                ),
                "{second} value"
            );
        }
        let language = Language::Portuguese;
        let error = language.message("sending attachment heartbeat: frame receive timeout");
        assert!(!error.contains("sending attachment") && !error.contains("frame receive timeout"));
        let detail = language.message("Authenticated Direct UDP · Incoming");
        assert!(!detail.contains("Authenticated") && !detail.contains("Incoming"));
        let retry =
            language.message("Channel closed · Retry queued (at least 4s; adaptive recovery)");
        assert!(
            !retry.contains("Channel closed")
                && !retry.contains("Retry queued")
                && retry.contains('4')
        );
    }
}
