# Third-party notices

EYYOBUILD includes the **Baritone Fabric 26.2** runtime as an intact nested Fabric mod:

- Component: `baritone-api-fabric-1.19.0.jar`
- Embedded path: `META-INF/jars/baritone-api-fabric-1.19.0.jar`
- Upstream project: https://github.com/cabaletta/baritone
- Corresponding source at the v1.19.0 release commit: https://github.com/cabaletta/baritone/tree/9fadf7cf95293d7f6678981e77a939b4a7978373
- Upstream release: https://github.com/cabaletta/baritone/releases/tag/v1.19.0
- SHA-256: `eca6e2fdf43c6657fe9fea10a8f9a7572d78dc91ad389b998b808bd28fa5b5d1`
- License: GNU Lesser General Public License v3.0 (see `LICENSES/Baritone-LICENSE.txt`).

Baritone is not copied into EYYOBUILD's own Java source tree. It remains a separate, replaceable nested Fabric mod. The bundled JAR is checksum-verified during the build and can be replaced with an interface-compatible Baritone build by replacing the nested JAR and updating the version metadata.

EYYOBUILD's own source code remains under the MIT License in the repository root `LICENSE`. The Baritone component is separately covered by its upstream license and notices.
