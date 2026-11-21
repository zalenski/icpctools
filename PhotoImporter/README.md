# Photo importer

Import Flickr albums or local files into CDS photo catalogs from a standalone CLI.
Source credentials, originals and image processing stay on the importer machine;
CDS stores display images and metadata through an authenticated HTTP API.
Standard distribution/container integration remains a later step.

The examples run from the repo root using the development JARs. See
[Building from source](#building-from-source) if they are not built yet.
`--data DIRECTORY` selects private configuration and cache storage; it defaults
to `photo-importer`.

## Setup, then import

Save the CDS connection using an admin or presentation-admin account:

```sh
java -cp 'PhotoImporter/staging/*' org.icpc.tools.photoimporter.PhotoImporter \
  --cds https://localhost:8443 --user admin --password PASSWORD --insecure-cds
```

`--insecure-cds` accepts a self-signed certificate only for CDS. Omit it for a
trusted certificate; `--secure-cds` restores normal certificate checking in saved
settings. Source TLS validation is unchanged. The positional alternative
`CDS_URL USER PASSWORD` is also accepted and saved.

Then save a Flickr key, configure an import, and run it:

```sh
java -cp 'PhotoImporter/staging/*' org.icpc.tools.photoimporter.PhotoImporter --flickr-key YOUR_KEY
java -cp 'PhotoImporter/staging/*' org.icpc.tools.photoimporter.PhotoImporter \
  --id team-photos --source flickr://album/ALBUM_ID --catalog teams --max-width 1920
java -cp 'PhotoImporter/staging/*' org.icpc.tools.photoimporter.PhotoImporter --run team-photos
```

A normal Flickr album/set URL works too. `--tag 'event$Team Photos'` filters an
album. Keys can be saved before configuring or contacting CDS. Create a Flickr
key at [Flickr's app setup page](https://www.flickr.com/services/apps/create/).

Configuration arguments always save their changes. Setup never starts imports;
`--run` does. You may combine setup and execution in one command. With no options,
the CLI lists configuration, key-profile IDs/status, local file paths and remote
CDS definition locations, without printing key values or passwords. If CDS is
unavailable, local settings are still shown.

`--profile NAME` selects a named key when saving credentials or configuring an
import. A short name such as `other` means `flickr/other`; omission uses
`flickr/default`. Editing an existing import preserves its profile unless an
explicit replacement is supplied. Import IDs are stable ownership identifiers;
keep the same ID when editing an album, filter or size limit.

Catalog `teams` means the CDS folder `photos-teams`. Omit `--catalog` when creating
an import to use `photos`, the default catalog. Editing a named catalog requires
`--catalog NAME`. Optional `--max-width` and `--max-height` preserve proportions
and never enlarge images; zero removes a saved limit. Changing limits reuses
originals and prepared copies wherever possible.

## Local files

No source credentials are needed:

```sh
java -cp 'PhotoImporter/staging/*' org.icpc.tools.photoimporter.PhotoImporter \
  --id local --source /absolute/path/to/photos --catalog teams --max-width 1920
java -cp 'PhotoImporter/staging/*' org.icpc.tools.photoimporter.PhotoImporter --run photos-teams/local
```

A `file:` URI or a single file works too. Folder scans include subdirectories.
The importer reads originals directly, without modifying them or making a second
original archive. Images must decode even without resizing. A persistent local
scan index records file paths, sizes, modification times and processing results;
unchanged valid and invalid files avoid repeated decoding across restarts.
Changed limits regenerate copies only when output dimensions change. Transient
failures and files that change during reading are retried; a corrupt replacement
preserves the last good copy. JSON and TMP files are skipped.

## Repeated runs and storage

Repeat `--run ID` to select several imports, use `--run all` for all definitions,
or plain `--run` for all (or the `--id` being configured). If an ID exists in more
than one catalog, use `photos[-name]/ID`. `--catalog NAME` restricts a run to that
catalog. Without a period each command makes one pass and exits:

```sh
java -cp 'PhotoImporter/staging/*' org.icpc.tools.photoimporter.PhotoImporter \
  --run photos-teams/team-photos --period 300
```

`--period` requires `--run`, has a minimum of 15 seconds and is never saved. The
next pass starts after the previous pass finishes and the period elapses. Failed
periodic passes are reported and retried; a failed single pass exits with an
error. Ctrl+C interrupts HTTP and checkpoints completed work. Image processing
may finish before stopping. Definitions and key profiles are reloaded each pass.

The data directory contains private `connection.json` and `credentials.json`.
Import definitions stay on CDS in `present/photos[-name]/photo-sources.json`,
without scheduling or activation flags. `--sources FILE [--catalog NAME]` installs
such a file. Legacy `enabled`/`refreshSeconds` fields are ignored and removed when
definitions are saved. Several imports can share a catalog; each owns only its
own photos, and manually placed files are preserved.

Target-specific `originals/` and `cache/` live under `targets/<target-id>/`.
Matching data from older CLI versions with a root `state.json` is reused.
Downloaded Flickr originals stay local; local-file originals are read in place.
Prepared images and resume indexes allow upload retries without fetching or
resizing again. Missing photos are pruned only after a complete listing/scan;
an unavailable source or interrupted scan preserves existing CDS photos.

Choose the catalog in the existing Photos presentation using `catalog:teams`, or
`catalog` for the default. The presentation picks up index changes as imported
photos become available.

See [API.md](API.md) for the CDS photo API reference.

## Building from source

Build CDS with the photo API, then build the importer (Java 17+):

```sh
ant -f ContestModel/build.xml jar
ant -f PhotoImporter/build.xml jar
```

For development in IntelliJ, run `org.icpc.tools.photoimporter.PhotoImporter`
in the `PhotoImporter` module, with the CLI options as program arguments.
