import { parseSnapshot, type Snapshot } from './model';

export class PhotoApi {
	private token = '';
	constructor(
		private root: string,
		private fetcher: typeof fetch = fetch
	) {}
	async state(): Promise<Snapshot> {
		const snapshot = parseSnapshot(await this.request('state'));
		this.token = snapshot.csrfToken;
		return snapshot;
	}
	async request(path: string, method = 'GET', body?: unknown): Promise<unknown> {
		const response = await this.fetcher(`${this.root}/${path}`, {
			method,
			credentials: 'same-origin',
			cache: 'no-store',
			headers: method === 'GET' ? {} : { 'Content-Type': 'application/json', 'X-Photo-Token': this.token },
			body: method === 'GET' ? undefined : JSON.stringify(body ?? {})
		});
		let data: unknown;
		try {
			data = await response.json();
		} catch {
			throw new Error(`Photo importer returned HTTP ${response.status} without a JSON response.`);
		}
		if (!response.ok) {
			const message = (data as { error?: unknown })?.error;
			throw new Error(
				typeof message === 'string' ? message : `Photo request failed (HTTP ${response.status}).`
			);
		}
		return data;
	}
	action(catalog: string, id: string, action: string, body = {}): Promise<unknown> {
		return this.request(
			`jobs/${encodeURIComponent(catalog)}/${encodeURIComponent(id)}/${action}`,
			'POST',
			body
		);
	}
}
