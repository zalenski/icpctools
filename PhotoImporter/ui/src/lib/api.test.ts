import { expect, it, vi } from 'vitest';
import { PhotoApi } from './api';
import { emptySnapshot } from './model';
it('uses the importer context and includes its token on mutations only', async () => {
	const fetcher = vi
		.fn<typeof fetch>()
		.mockResolvedValueOnce(Response.json({ ...emptySnapshot, csrfToken: 'test-token' }))
		.mockResolvedValueOnce(Response.json({}));
	const api = new PhotoApi('/nested/photo-importer/api', fetcher);
	await api.state();
	await api.action('photos', 'teams', 'run');
	expect(fetcher.mock.calls[0][0]).toBe('/nested/photo-importer/api/state');
	expect(fetcher.mock.calls[1][1]?.headers).toEqual({
		'Content-Type': 'application/json',
		'X-Photo-Token': 'test-token'
	});
	expect(JSON.parse(fetcher.mock.calls[1][1]?.body as string)).toEqual({});
});
it('preserves useful API errors and distinguishes broken responses', async () => {
	const api = new PhotoApi(
		'/api',
		vi
			.fn<typeof fetch>()
			.mockResolvedValueOnce(Response.json({ error: 'Source is already running' }, { status: 409 }))
			.mockResolvedValueOnce(new Response('<html>error</html>', { status: 502 }))
	);
	await expect(api.request('jobs/a/b/run', 'POST')).rejects.toThrow('Source is already running');
	await expect(api.request('state')).rejects.toThrow('without a JSON response');
});
