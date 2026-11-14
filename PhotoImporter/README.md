# Photo importer (development)

Runs independently of CDS. `PhotoImporter` depends only on ContestModel; CDS has
no Flickr integration or image-processing worker. A web UI and distribution
packaging are later steps.

Build after ContestModel:

```sh
ant -f ContestModel/build.xml jar
ant -f PhotoImporter/build.xml jar
```

In IntelliJ, run `org.icpc.tools.photoimporter.PhotoImporter` in module
`PhotoImporter`, with program arguments:

```text
https://localhost:8443 admin PASSWORD --data /path/to/photo-importer --once --insecure-cds
```

URL, user and password are positional arguments, using the same `ArgumentParser`
as the other clients. `--insecure-cds` accepts a self-signed certificate for the target CDS only;
omit it for a trusted certificate. Omit `--once` to poll enabled imports continuously.
CDS must be rebuilt with the photo API before using the importer.

For a private CA, Java also accepts a truststore through VM options:
`-Djavax.net.ssl.trustStore=/path/to/truststore.p12` and
`-Djavax.net.ssl.trustStorePassword=PASSWORD`. Run without `--insecure-cds` in
that case. This replaces the JVM's default truststore for all normal TLS clients,
so include public roots needed by Flickr too. Hostname verification still applies.
See the [JSSE guide](https://docs.oracle.com/en/java/javase/17/security/java-secure-socket-extension-jsse-reference-guide.html).

HTTP uses reusable JDK `HttpClient` instances with a 15-second connect timeout
and a 60-second request timeout (replacing the previous read timeout). Source
requests follow redirects except HTTPS-to-HTTP; authenticated CDS requests do not.

The same main class runs from the CLI with classpath `PhotoImporter/staging/*`.
The data directory contains `credentials.json` (named Flickr profiles), `originals/`
and `cache/`, separated by catalog. The cache holds display copies and a resume
index, allowing upload retries without downloading or resizing again.

Definitions remain on CDS in each catalog's `photo-sources.json`. Use
`--catalog photos-teams --sources /path/to/photo-sources.json` to install a file
through the API before syncing. Source URIs support Flickr albums and `file:` URLs
for a local file or folder. Local sources need no credential profile. Import IDs
use letters, digits, underscores and hyphens. Originals always stay on the importer.

## CDS API

Requires an existing admin or presentation-admin account. All catalog access uses
HTTP, even when importer and CDS run on the same host.

- `GET /api/photos/catalogs`: list catalog folder names (`photos`, `photos-teams`, …).
- `GET/PUT /api/photos/catalogs/{catalog}/sources`: import definitions.
- `GET /api/photos/catalogs/{catalog}/index`: stored photo metadata.
- `POST /api/photos/catalogs/{catalog}/imports/{id}/photos`: multipart `metadata`
  JSON and `photo` bytes; returns stored metadata, including the allocated filename.
- `DELETE /api/photos/catalogs/{catalog}/imports/{id}/photos?photoId=…`: delete one
  indexed photo belonging to that import.

CDS preserves manually placed files and other imports. Uploads and index files use
atomic replacement; each completed upload is registered on CDS immediately.
The importer publishes its local checkpoints about every ten seconds and on
completion. Only a complete source listing permits removal of old remote photos.
