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
omit it for a trusted certificate. `--once` explicitly runs all enabled imports once.
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
and `cache/`, separated by catalog, plus importer-owned `state.json`. The cache holds display copies and a resume
index, allowing upload retries without downloading or resizing again.

Definitions remain on CDS in each catalog's `photo-sources.json`. Use
`--catalog photos-teams --sources /path/to/photo-sources.json` to install a file
through the API before syncing. Source URIs support Flickr albums and `file:` URLs
for a local file or folder. Local sources need no credential profile. Import IDs
use letters, digits, underscores and hyphens. Originals always stay on the importer.

## Jobs (CLI and future UI)

A new importer discovers definitions without automatically taking over their jobs.
For continuous sync, explicitly select jobs; repeat `--start` for several imports:

```text
https://localhost:8443 admin PASSWORD --data /path/to/photo-importer --start photos-teams/team-photos --resume-on-restart --insecure-cds
```

`--resume-on-restart` opts those jobs into automatic restart. Without it, their
selection is saved but they remain stopped after restarting the importer. Other
enabled CDS definitions remain available to select. Source `enabled` must also be
true. Ctrl+C stops current work; restart preferences are retained.

`PhotoSyncService` exposes `poll()`, `status()`, `start(catalog, id, resumeOnRestart)`,
`stop(catalog, id)`, `retry(catalog, id)` and `close()` for the later web UI. Status
snapshots contain the phase, counts, errors and last successful run. Polling reloads
definitions and credentials without waiting for imports. Imports share one worker
to serialize local catalog-index writes. Changed definitions/keys cancel the old
run before its replacement; disabled or removed definitions stop importing.

Job selections, restart preferences and last-success times are written atomically
to `state.json`, bound to the target CDS URL. Use a separate data directory for a
different target. Secrets remain in `credentials.json`; definitions stay on CDS.

Stop interrupts pending HTTP and checks cancellation between other operations.
Image decoding/scaling may finish before stopping. Completed photos remain usable;
cancellation checkpoints completed local work and never prunes unvisited photos.
An upload already received by CDS may finish even after the job stops. Retry reuses
local originals and prepared images. A stopped job is not automatically retried;
selected active jobs continue polling after ordinary failures.

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
