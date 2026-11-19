package org.icpc.tools.photoimporter;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.ArrayList;

import org.icpc.tools.contest.Trace;
import org.icpc.tools.contest.model.feed.JSONParser.JsonObject;
import org.icpc.tools.contest.model.photos.PhotoMetadata;
import org.icpc.tools.contest.model.util.ArgumentParser;
import org.icpc.tools.contest.model.util.ArgumentParser.OptionParser;
import org.icpc.tools.contest.model.util.ArgumentParser.Source;

/** Direct CLI/IntelliJ entry point. The web launcher will use the same service. */
public final class PhotoImporter {
	private PhotoImporter() {
		// entry point
	}

	public static void main(String[] args) throws Exception {
		File[] dataOption = { new File("photo-importer") };
		File[] definitionsOption = new File[1];
		String[] catalogOption = new String[1];
		boolean[] onceOption = new boolean[1];
		boolean[] insecureOption = new boolean[1];
		boolean[] resumeOption = new boolean[1];
		List<String> startOptions = new ArrayList<>();
		Source source = ArgumentParser.parseSource(args, new OptionParser() {
			@Override
			public boolean setOption(String option, List<Object> options) {
				switch (option) {
					case "--data":
						ArgumentParser.expectOptions(option, options, "directory:string");
						dataOption[0] = new File((String) options.get(0));
						return true;
					case "--catalog":
						ArgumentParser.expectOptions(option, options, "catalog:string");
						catalogOption[0] = (String) options.get(0);
						return true;
					case "--sources":
						ArgumentParser.expectOptions(option, options, "file:string");
						definitionsOption[0] = new File((String) options.get(0));
						return true;
					case "--start":
						ArgumentParser.expectOptions(option, options, "catalog/import:string");
						startOptions.add((String) options.get(0));
						return true;
					case "--resume-on-restart":
						resumeOption[0] = true;
						return true;
					case "--once":
						onceOption[0] = true;
						return true;
					case "--insecure-cds":
						insecureOption[0] = true;
						return true;
					default:
						return false;
				}
			}

			@Override
			public void showHelp() {
				help();
			}
		});
		if (source == null) {
			help();
			return;
		}
		if (source.src.length != 1) {
			throw new IllegalArgumentException("Expected one target CDS");
		}
		String url = source.src[0];
		File data = dataOption[0];
		File definitions = definitionsOption[0];
		String catalog = catalogOption[0];
		boolean once = onceOption[0];
		boolean insecure = insecureOption[0];
		if ((catalog == null) != (definitions == null)) {
			throw new IllegalArgumentException("Use --catalog and --sources together to install definitions");
		}
		if (resumeOption[0] && startOptions.isEmpty()) {
			throw new IllegalArgumentException("Use --resume-on-restart with --start");
		}
		if (once && !startOptions.isEmpty()) {
			throw new IllegalArgumentException("Use --once for all enabled imports, or --start for selected jobs");
		}
		for (String id : startOptions) {
			if (!id.matches("photos(?:-[A-Za-z0-9_-]+)?/[A-Za-z0-9_-]+")) {
				throw new IllegalArgumentException("Expected --start photos[-name]/import-id");
			}
		}
		CDSPhotoClient cds = new CDSPhotoClient(URI.create(url), source.user, source.password, insecure);
		if (definitions != null) {
			JsonObject json = PhotoMetadata.readObject(definitions);
			PhotoSources.parse(json);
			cds.sources(catalog, json);
		}
		try (PhotoSyncService service = new PhotoSyncService(cds, data)) {
			Thread shutdown = new Thread(service::close, "Photo importer shutdown");
			Runtime.getRuntime().addShutdownHook(shutdown);
			try {
				Trace.trace(Trace.USER, "Photo importer targeting " + url + ", local data: " + data.getAbsolutePath());
				boolean started = false;
				do {
					try {
						if (once) {
							if (!service.tick()) {
								throw new IOException("One or more photo imports failed");
							}
						} else {
							service.poll();
							if (!started) {
								for (String id : startOptions) {
									String[] parts = id.split("/");
									service.start(parts[0], parts[1], resumeOption[0]);
								}
								started = true;
							}
						}
					} catch (Exception e) {
						PhotoSync.checkCancelled();
						Trace.trace(Trace.WARNING, "Photo import failed: " + PhotoHttp.redact(e.getMessage()));
						if (once) {
							throw e;
						}
					}
					if (!once) {
						Thread.sleep(10000);
					}
				} while (!once);
			} finally {
				try {
					Runtime.getRuntime().removeShutdownHook(shutdown);
				} catch (IllegalStateException e) {
					// JVM shutdown is already in progress.
				}
			}
		}
	}

	private static void help() {
		System.out.println("PhotoImporter cdsURL user password [--data DIRECTORY] [--once] [--insecure-cds]");
		System.out.println("  --data defaults to photo-importer; contains credentials.json, state.json, originals/ and cache/.");
		System.out.println("  --catalog photos[-name] --sources FILE installs definitions on the target CDS.");
		System.out.println("  --once runs one polling cycle; otherwise only locally selected jobs run.");
		System.out.println("  --start photos[-name]/import-id selects a job; repeat for multiple jobs.");
		System.out.println("  --resume-on-restart saves automatic restart for jobs selected with --start.");
		System.out.println("  --insecure-cds accepts a self-signed CDS certificate; source TLS validation is unchanged.");
	}
}
