import { describe, expect, it } from 'vitest';
import {
	catalogId,
	emptySnapshot,
	parseSnapshot,
	positive,
	reconcile,
	sourcePayload,
	stateLabel,
	type SourceDraft
} from './model';
const draft: SourceDraft = {
	id: 'teams',
	catalog: 'teams',
	kind: 'file',
	uri: '/photos/Team photos',
	profile: 'flickr/default',
	tag: '',
	maxWidth: '',
	maxHeight: '',
	enabled: false,
	runHere: false,
	resumeOnRestart: false
};
describe('import definitions', () => {
	it('normalizes local paths without credentials and does not enable jobs implicitly', () => {
		const payload = sourcePayload(draft);
		expect(payload.destination).toBe('photos-teams');
		expect(payload.source.source).toBe('file:///photos/Team%20photos');
		expect(payload.source.credentialProfile).toBeNull();
		expect(payload.source.enabled).toBe(false);
		expect(payload.runHere).toBe(false);
	});
	it('rejects invalid identifiers, relative paths and non-Flickr remote URLs', () => {
		expect(() => sourcePayload({ ...draft, id: '../bad' })).toThrow();
		expect(() => sourcePayload({ ...draft, uri: 'relative/path' })).toThrow();
		expect(() => sourcePayload({ ...draft, kind: 'flickr', uri: 'https://example.com/albums/1' })).toThrow();
		expect(() => positive('-1', 'Width')).toThrow();
		expect(() => positive('1.5', 'Width')).toThrow();
		expect(catalogId('')).toBe('photos');
		expect(() => catalogId('../other')).toThrow();
	});
	it('accepts Flickr URLs, filters and explicit periodic selection', () => {
		const payload = sourcePayload({
			...draft,
			kind: 'flickr',
			uri: 'https://www.flickr.com/photos/icpc/albums/42/',
			tag: ' Team Photos ',
			enabled: true,
			runHere: true,
			maxWidth: '1920'
		});
		expect(payload.source.credentialProfile).toBe('flickr/default');
		expect(payload.source.tag).toBe('Team Photos');
		expect(payload.source.maxWidth).toBe(1920);
		expect(payload.runHere).toBe(true);
	});
});
describe('polling snapshots', () => {
	it('normalizes numeric strings from the Java JSON writer', () => {
		const snapshot = parseSnapshot({
			...emptySnapshot,
			csrfToken: 'token',
			catalogs: [{ id: 'photos', refreshSeconds: '30', sources: [] }]
		});
		expect(snapshot.catalogs[0].refreshSeconds).toBe(30);
		expect(() => parseSnapshot({ error: 'bad' })).toThrow();
	});
	it('preserves all references when nothing has changed', () => {
		const previous = structuredClone(emptySnapshot);
		expect(reconcile(previous, structuredClone(previous))).toBe(previous);
	});
	it('preserves unchanged catalog and row references when another row changes', () => {
		const before = parseSnapshot({
			...emptySnapshot,
			csrfToken: 'token',
			catalogs: [{ id: 'photos', refreshSeconds: 30, sources: [] }],
			jobs: [
				{ catalog: 'photos', importId: 'a', progress: {} },
				{ catalog: 'photos', importId: 'b', progress: {} }
			]
		});
		const next = structuredClone(before);
		next.jobs[1].uploaded = 3;
		const updated = reconcile(before, next);
		expect(updated.catalogs).toBe(before.catalogs);
		expect(updated.jobs[0]).toBe(before.jobs[0]);
		expect(updated.jobs[1]).not.toBe(before.jobs[1]);
	});
	it('keeps source inactivity distinct from local job selection', () => {
		const source = sourcePayload(draft).source;
		expect(stateLabel(source)).toBe('Inactive');
		expect(stateLabel({ ...source, enabled: true })).toBe('Available');
	});
});
