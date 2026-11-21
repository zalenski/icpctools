import adapter from '@sveltejs/adapter-static';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';
import { sveltekit } from '@sveltejs/kit/vite';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig, type ProxyOptions } from 'vite';

const target = process.env.IMPORTER_URL || 'http://127.0.0.1:8787';
const base = (process.env.PHOTO_IMPORTER_BASE || '/photo-importer') as `/${string}`;
const proxy: Record<string, ProxyOptions> = {
	[`${base}/api`]: {
		target,
		changeOrigin: true,
		configure(proxy) {
			proxy.on('proxyReq', (request) => {
				request.setHeader('Origin', new URL(target).origin);
			});
		}
	}
};
export default defineConfig({
	plugins: [
		tailwindcss(),
		sveltekit({
			preprocess: vitePreprocess(),
			adapter: adapter({ pages: 'build', assets: 'build', fallback: 'index.html' }),
			paths: { base }
		})
	],
	server: { proxy },
	preview: { proxy }
});
