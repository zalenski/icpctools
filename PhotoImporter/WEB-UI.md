# Photo importer web UI

The optional web UI uses the same import engine, private connection/key files
and image caches as the [CLI](README.md). It runs independently of CDS and serves
its frontend locally; import workers continue after the browser closes.

## Start and connect

After [building the web launcher](#building-from-source), run from the repo root:

```sh
java -cp 'PhotoImporter/staging/*' org.icpc.tools.photoimporter.PhotoWebServer --data photo-importer
```

Open `http://127.0.0.1:8787/photo-importer/`. Connect to CDS with an admin or
presentation-admin account. For a local self-signed CDS, select the insecure-CDS
option; source TLS verification is unchanged. Flickr keys can be configured
independently of connecting to CDS or starting an import.

Create a catalog, add Flickr or local sources, run imports and inspect progress,
photo results and failures. Local paths refer to the importer machine. Run now
works independently of periodic scheduling, including for inactive sources.
Removing a source preserves its imported photos.

## Local scheduling and storage

Activation, refresh interval and restart preferences belong to each source job
on this importer and are saved only in its target-specific `state.json`. New
imports are not automatically scheduled. Restoring periodic jobs requires
explicit resume-on-restart selection. CDS definitions contain no scheduler
settings; another importer makes its own scheduling choices.

The `--data` directory stores private CDS credentials in `connection.json` and
Flickr profiles in `credentials.json`. Browser responses never contain those
secrets. Target-specific job data and image caches live under
`targets/<target-id>/`; matching data from older CLI versions is reused at the
data root. Profiles are shared across targets. CLI execution ignores saved UI
jobs, while reusing connection/key files and prepared images.

## Launcher options

The launcher binds to localhost. `--port PORT` changes the default 8787 listener;
`--ui DIRECTORY` overrides the frontend directory. `--context PATH` must match
the frontend's `PHOTO_IMPORTER_BASE` build setting (default `/photo-importer`).
Liberty/WAR hosting and standard distribution/container integration remain
follow-up work.

## Building from source

Build CDS with the [photo API](API.md). Build the frontend with Node 24+ and pnpm,
then package the standalone launcher:

```sh
cd PhotoImporter/ui
pnpm install
pnpm check
pnpm test
cd ../..
ant -f ContestModel/build.xml jar
ant -f PhotoImporter/build.xml web
```

In IntelliJ, run `org.icpc.tools.photoimporter.PhotoWebServer` in module
`PhotoImporter` after `pnpm build`. It reads `PhotoImporter/ui/build` when the
working directory is the repo root, or use `--ui DIRECTORY`.

For frontend development, start the launcher, then `pnpm dev` in
`PhotoImporter/ui`. Vite proxies `/photo-importer/api` to
`http://127.0.0.1:8787` (override using `IMPORTER_URL`). Vitest tests behavior;
`pnpm check` and `pnpm build` check types and production output. Java serves
static output, with no Node server needed at runtime.
