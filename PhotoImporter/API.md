# CDS photo API

All catalog access uses HTTP, including co-hosted operation, and requires an admin
or presentation-admin account:

- `GET /api/photos/catalogs`: list catalogs.
- `GET/PUT /api/photos/catalogs/{catalog}/sources`: import definitions.
- `GET /api/photos/catalogs/{catalog}/index`: indexed metadata.
- `POST /api/photos/catalogs/{catalog}/imports/{id}/photos`: multipart `metadata`
  and display-image bytes; returns metadata with the allocated filename.
- `PUT /api/photos/catalogs/{catalog}/imports/{id}/photos`: metadata-only update;
  returns 404 if the photo/file is missing so the importer can upload it again.
- `DELETE /api/photos/catalogs/{catalog}/imports/{id}/photos?photoId=…`: remove one
  indexed photo belonging to this import.

Uploads/indexes use atomic replacement. Each completed upload is available
immediately; local checkpoints are saved during processing and on completion.
CDS preserves manually placed files and other imports.

See [the CLI guide](README.md) for setup and import commands.
