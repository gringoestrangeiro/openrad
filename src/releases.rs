//! Bounded release checks. Failures are optional background information; an
//! already discovered update is retained by the presentation layer.
use anyhow::{ensure, Result};
use serde::Deserialize;
use std::time::Duration;

pub const CHECK_INTERVAL: Duration = Duration::from_secs(60 * 60);
const RELEASE_PREFIX: &str = "https://github.com/gringoestrangeiro/openrad/releases/tag/";
const RESPONSE_LIMIT: u64 = 1024 * 1024;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Release {
    pub version: String,
    pub url: String,
}

#[derive(Deserialize)]
struct GitHubRelease {
    tag_name: String,
    html_url: String,
    draft: bool,
    prerelease: bool,
}

pub fn parse_latest(bytes: &[u8], current: &str) -> Result<Option<Release>> {
    ensure!(
        bytes.len() as u64 <= RESPONSE_LIMIT,
        "Release response is too large"
    );
    let release: GitHubRelease = serde_json::from_slice(bytes)?;
    let version = semver::Version::parse(
        release
            .tag_name
            .strip_prefix('v')
            .unwrap_or(&release.tag_name),
    )?;
    let current = semver::Version::parse(current.strip_prefix('v').unwrap_or(current))?;
    if release.draft
        || release.prerelease
        || !version.pre.is_empty()
        || version.cmp_precedence(&current) != std::cmp::Ordering::Greater
    {
        return Ok(None);
    }
    ensure!(
        release.html_url.starts_with(RELEASE_PREFIX)
            && !release.html_url.chars().any(char::is_control),
        "Invalid release link"
    );
    Ok(Some(Release {
        version: release.tag_name,
        url: release.html_url,
    }))
}

#[path = "platform/release_http.rs"]
mod release_http;

pub fn latest(current: &str) -> Result<Option<Release>> {
    parse_latest(&release_http::fetch_latest()?, current)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    fn release(tag: &str) -> Vec<u8> {
        serde_json::to_vec(
            &json!({"tag_name":tag, "html_url":format!("{RELEASE_PREFIX}{tag}"),
            "draft":false,"prerelease":false}),
        )
        .unwrap()
    }
    #[test]
    fn newer_releases_use_numeric_version_order_and_accept_v_prefix() {
        assert!(parse_latest(&release("v0.10.0"), "0.9.5")
            .unwrap()
            .is_some());
        assert!(parse_latest(&release("0.9.5"), "v0.9.5").unwrap().is_none());
        assert!(parse_latest(&release("0.9.4"), "0.9.5").unwrap().is_none());
        assert!(parse_latest(&release("v1.0.0-rc.1"), "0.9.5")
            .unwrap()
            .is_none());
        assert!(parse_latest(&release("garbage"), "0.9.5").is_err());
        assert!(parse_latest(b"not json", "0.9.5").is_err());
    }
    #[test]
    fn untrusted_release_links_are_rejected() {
        let bytes = serde_json::to_vec(
            &json!({"tag_name":"v1.0.0","html_url":"https://evil.example/",
            "draft":false,"prerelease":false}),
        )
        .unwrap();
        assert!(parse_latest(&bytes, "0.9.5").is_err());
    }
}
