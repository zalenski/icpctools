export interface SourceConfig {
	id: string;
	source: string;
	credentialProfile: string | null;
	tag: string | null;
	maxWidth: number | null;
	maxHeight: number | null;
	enabled: boolean;
}
export interface Catalog {
	available: number;
	id: string;
	refreshSeconds: number;
	sources: SourceConfig[];
}
export interface Job {
	catalog: string;
	importId: string;
	state: string;
	selected: boolean;
	periodic: boolean;
	resumeOnRestart: boolean;
	nextRun: number;
	lastSuccess: number;
	available: number;
	uploaded: number;
	removed: number;
	error: string | null;
	progress: {
		phase: string;
		processed: number;
		total: number;
		downloaded: number;
		failed: number;
		photoId: string | null;
	};
}
export interface Snapshot {
	configured: boolean;
	loading: boolean;
	error: string | null;
	dataDirectory: string;
	connection: { url: string; user: string; insecureCds: boolean; passwordConfigured: boolean };
	profiles: string[];
	catalogs: Catalog[];
	jobs: Job[];
	csrfToken: string;
}
export interface SourceDraft {
	id: string;
	catalog: string;
	kind: 'file' | 'flickr';
	uri: string;
	profile: string;
	tag: string;
	maxWidth: string;
	maxHeight: string;
	enabled: boolean;
	runHere: boolean;
	resumeOnRestart: boolean;
}
export const emptySnapshot: Snapshot = {
	configured: false,
	loading: false,
	error: null,
	dataDirectory: '',
	connection: { url: '', user: '', insecureCds: false, passwordConfigured: false },
	profiles: [],
	catalogs: [],
	jobs: [],
	csrfToken: ''
};
const object = (value: unknown): Record<string, unknown> => {
	if (value === null || typeof value !== 'object' || Array.isArray(value)) {
		throw new Error('Invalid response from the photo importer');
	}
	return value as Record<string, unknown>;
};
const array = (value: unknown): unknown[] => (Array.isArray(value) ? value : []);
const string = (value: unknown): string => (typeof value === 'string' ? value : '');
export const numeric = (value: unknown, fallback = 0): number => {
	if (value === null || value === undefined || value === '') {
		return fallback;
	}
	const result = Number(value);
	return Number.isFinite(result) ? result : fallback;
};
export function parseSnapshot(input: unknown): Snapshot {
	const data = object(input);
	if (typeof data.configured !== 'boolean' || typeof data.csrfToken !== 'string') {
		throw new Error('Invalid response from the photo importer');
	}
	const connection = object(data.connection);
	return {
		configured: data.configured,
		loading: data.loading === true,
		error: string(data.error) || null,
		dataDirectory: string(data.dataDirectory),
		csrfToken: data.csrfToken,
		connection: {
			url: string(connection.url),
			user: string(connection.user),
			insecureCds: connection.insecureCds === true,
			passwordConfigured: connection.passwordConfigured === true
		},
		profiles: array(data.profiles).filter((item): item is string => typeof item === 'string'),
		catalogs: array(data.catalogs).map((value) => {
			const catalog = object(value);
			return {
				id: string(catalog.id),
				available: numeric(catalog.available, -1),
				refreshSeconds: numeric(catalog.refreshSeconds, 300),
				sources: array(catalog.sources).map((value) => {
					const source = object(value);
					return {
						id: string(source.id),
						source: string(source.source),
						credentialProfile: string(source.credentialProfile) || null,
						tag: string(source.tag) || null,
						maxWidth: numeric(source.maxWidth) || null,
						maxHeight: numeric(source.maxHeight) || null,
						enabled: source.enabled === true
					};
				})
			};
		}),
		jobs: array(data.jobs).map((value) => {
			const job = object(value);
			const progress = object(job.progress);
			return {
				catalog: string(job.catalog),
				importId: string(job.importId),
				state: string(job.state),
				selected: job.selected === true,
				periodic: job.periodic === true,
				resumeOnRestart: job.resumeOnRestart === true,
				nextRun: numeric(job.nextRun),
				lastSuccess: numeric(job.lastSuccess),
				available: numeric(job.available, -1),
				uploaded: numeric(job.uploaded),
				removed: numeric(job.removed),
				error: string(job.error) || null,
				progress: {
					phase: string(progress.phase),
					processed: numeric(progress.processed),
					total: numeric(progress.total),
					downloaded: numeric(progress.downloaded),
					failed: numeric(progress.failed),
					photoId: string(progress.photoId) || null
				}
			};
		})
	};
}
export const jobKey = (job: { catalog: string; importId: string }): string =>
	`${job.catalog}/${job.importId}`;
export const running = (job?: Job): boolean => !!job && ['QUEUED', 'RUNNING', 'STOPPING'].includes(job.state);
const equal = (a: unknown, b: unknown): boolean => JSON.stringify(a) === JSON.stringify(b);
function retain<T>(previous: T[], next: T[], key: (item: T) => string): T[] {
	const before = new Map(previous.map((item) => [key(item), item]));
	const result = next.map((item) => {
		const old = before.get(key(item));
		return old && equal(old, item) ? old : item;
	});
	return result.length === previous.length && result.every((item, i) => item === previous[i])
		? previous
		: result;
}
/** Preserve references for unchanged data; keyed Svelte rows keep their existing DOM nodes. */
export function reconcile(previous: Snapshot, next: Snapshot): Snapshot {
	if (equal(previous, next)) {
		return previous;
	}
	return {
		...next,
		catalogs: retain(previous.catalogs, next.catalogs, (item) => item.id),
		jobs: retain(previous.jobs, next.jobs, jobKey),
		profiles: equal(previous.profiles, next.profiles) ? previous.profiles : next.profiles
	};
}
export function catalogId(value: string): string {
	const name = value.trim();
	const id =
		name === '' || name === 'photos' ? 'photos' : name.startsWith('photos-') ? name : `photos-${name}`;
	if (!/^photos(?:-[A-Za-z0-9][A-Za-z0-9_-]{0,31})?$/.test(id)) {
		throw new Error('Use a catalog name of 1–32 letters, numbers, underscores or hyphens.');
	}
	return id;
}
export function positive(value: string, label: string): number | undefined {
	if (value.trim() === '') {
		return undefined;
	}
	const number = Number(value);
	if (!Number.isSafeInteger(number) || number < 1 || number > 2147483647) {
		throw new Error(`${label} must be a positive whole number.`);
	}
	return number;
}
export function sourcePayload(draft: SourceDraft): {
	source: SourceConfig;
	destination: string;
	runHere: boolean;
	resumeOnRestart: boolean;
} {
	if (!/^[A-Za-z0-9_-]+$/.test(draft.id)) {
		throw new Error('Source ID may contain letters, numbers, underscores and hyphens.');
	}
	let source = draft.uri.trim();
	if (draft.kind === 'file') {
		if (source.startsWith('/') || /^[A-Za-z]:[\\/]/.test(source)) {
			const url = new URL('file:///');
			url.pathname = source.replaceAll('\\', '/').replace(/^([A-Za-z]:)/, '/$1');
			source = url.href;
		}
		const url = new URL(source);
		if (url.protocol !== 'file:' || url.search || url.hash || url.host) {
			throw new Error('Use an absolute path or file: URL on the importer machine.');
		}
	} else if (!/^flickr:\/\/album\/\d+\/?$/.test(source)) {
		const url = new URL(source);
		if (
			!['http:', 'https:'].includes(url.protocol) ||
			!['flickr.com', 'www.flickr.com'].includes(url.hostname) ||
			!/^\/photos\/[^/]+\/(albums|sets)\/\d+\/?$/.test(url.pathname) ||
			url.username ||
			url.password
		) {
			throw new Error('Use a Flickr album URL or flickr://album/ALBUM_ID.');
		}
	}
	return {
		destination: catalogId(draft.catalog),
		runHere: draft.enabled && draft.runHere,
		resumeOnRestart: draft.resumeOnRestart,
		source: {
			id: draft.id,
			source,
			credentialProfile: draft.kind === 'file' ? null : draft.profile || null,
			tag: draft.kind === 'file' ? null : draft.tag.trim() || null,
			maxWidth: positive(draft.maxWidth, 'Width') ?? null,
			maxHeight: positive(draft.maxHeight, 'Height') ?? null,
			enabled: draft.enabled
		}
	};
}
export function timestamp(value: number): string {
	return value > 0
		? new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(value)
		: '—';
}
export function stateLabel(source: SourceConfig, job?: Job): string {
	if (running(job)) {
		return job?.state === 'STOPPING' ? 'Stopping' : job?.state === 'QUEUED' ? 'Queued' : 'Running';
	}
	if (job?.error) {
		return job.state === 'MISSING_CREDENTIALS' ? 'Needs credentials' : 'Needs attention';
	}
	if (!source.enabled) {
		return 'Inactive';
	}
	return job?.periodic ? 'Scheduled' : 'Available';
}
