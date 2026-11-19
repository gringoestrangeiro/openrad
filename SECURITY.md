# Security

OpenRad is an unofficial interoperable implementation of a proprietary VPN
protocol. Compatibility does not imply a modern audited security protocol.
The current stable release is listed on the [releases page](https://github.com/gringoestrangeiro/openrad/releases).
Development branches are not security-supported production releases.

## Reporting a vulnerability

Use the repository's [Security page](https://github.com/gringoestrangeiro/openrad/security)
and its private vulnerability-reporting option when available. If it is not
available, ask the maintainer for a private reporting channel without disclosing
technical details in a public issue. Do not post real credentials, profile
directories, packet captures, private keys, or unredacted personal logs.

Include the version or commit, platform, affected component, impact, and a
minimal reproduction using synthetic data or a disposable local test setup.
The project has one primary maintainer; response times are not guaranteed.

Testing infrastructure or accounts belonging to third parties requires their
own authorization. Prefer the local coordinator and the repository's synthetic
fixtures for testing OpenRad.

## Security boundaries

- The original wire protocol and its cryptography remain compatibility
  constraints; neither this project nor its compatibility tests provide an
  independent cryptographic audit.
- Prefer a coordinator you control when operational independence matters.
  The official server is not operated by the OpenRad project.
- Native driver, elevated Windows startup and real official-client
  interoperability require separately documented qualification.
- For dependency review and release gates, see
  [maintenance policy](docs/maintenance.md).
