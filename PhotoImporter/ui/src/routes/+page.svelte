<script lang="ts">
	import { onMount } from 'svelte';
	import { resolve } from '$app/paths';
	import { toggleMode } from 'mode-watcher';
	import ICPCtools from '@icpctools/contest-ui/ICPCtools';
	import { PhotoApi } from '../lib/api';
	import {
		emptySnapshot,
		jobKey,
		reconcile,
		type Catalog,
		type Job,
		type SourceConfig,
		type Snapshot
	} from '../lib/model';
	import SourceRow from '../lib/SourceRow.svelte';
	import SourceEditor from '../lib/SourceEditor.svelte';
	import ConnectionEditor from '../lib/ConnectionEditor.svelte';
	import CredentialsEditor from '../lib/CredentialsEditor.svelte';
	import CatalogEditor from '../lib/CatalogEditor.svelte';
	const base = resolve('/').replace(/\/$/, '');
	const api = new PhotoApi(`${base}/api`);
	let snapshot: Snapshot = $state(emptySnapshot);
	let loaded = $state(false);
	let pollError = $state('');
	let notice = $state('');
	let actionError = $state('');
	let connectionOpen = $state(false);
	let credentialsOpen = $state(false);
	let catalogEditor: { catalog?: Catalog } | null = $state(null);
	let sourceEditor: { catalog: string; source?: SourceConfig; job?: Job } | null = $state(null);
	let busy: Record<string, boolean> = $state({});
	let sequence = 0;
	let jobs = $derived(new Map(snapshot.jobs.map((job) => [jobKey(job), job])));
	let total = $derived(
		snapshot.catalogs.reduce((count, catalog) => count + Math.max(0, catalog.available), 0)
	);
	async function load() {
		const request = ++sequence;
		try {
			const next = await api.state();
			if (request === sequence) {
				snapshot = reconcile(snapshot, next);
				loaded = true;
				pollError = '';
			}
		} catch (e) {
			if (request === sequence) {
				pollError = e instanceof Error ? e.message : 'Importer unavailable';
			}
		}
	}
	onMount(() => {
		let stopped = false;
		let timer: ReturnType<typeof setTimeout>;
		async function poll() {
			await load();
			if (!stopped) {
				timer = setTimeout(poll, 2000);
			}
		}
		void poll();
		return () => {
			stopped = true;
			clearTimeout(timer);
			sequence++;
		};
	});
	async function action(
		catalog: string,
		source: SourceConfig,
		job: Job | undefined,
		name: string,
		body = {}
	) {
		const key = `${catalog}/${source.id}`;
		busy[key] = true;
		actionError = '';
		notice = '';
		try {
			await api.action(catalog, source.id, name, body);
			if (name === 'activation' && (body as { enabled?: boolean }).enabled && !job?.periodic) {
				await api.action(catalog, source.id, 'start', { resumeOnRestart: job?.resumeOnRestart || false });
			}
			await load();
		} catch (e) {
			actionError = e instanceof Error ? e.message : 'Action failed';
		} finally {
			busy[key] = false;
		}
	}
	async function removeSource(catalog: string, source: SourceConfig) {
		if (!confirm(`Remove ${source.id}? Its imported photos will remain available on CDS.`)) {
			return;
		}
		actionError = '';
		notice = '';
		try {
			await api.request(
				`catalogs/${encodeURIComponent(catalog)}/sources/${encodeURIComponent(source.id)}`,
				'DELETE'
			);
			notice = 'Source removed. Imported photos were kept.';
			await load();
		} catch (e) {
			actionError = e instanceof Error ? e.message : 'Could not remove source';
		}
	}
</script>

<svelte:head
	><title>Photo importer · ICPC tools</title><meta
		name="description"
		content="Manage photo catalogs, import sources and sync jobs for your CDS."
	/></svelte:head
>
<header class="app-header">
	<div class="header-inner">
		<a class="brand" href={`${base}/`} aria-label="ICPC tools Photo importer"
			><span class="brand-mark"><ICPCtools /></span><span>Photo importer</span></a
		>
		<nav aria-label="Settings">
			<button class="header-button" onclick={() => (credentialsOpen = true)}
				><i class="fa-solid fa-key" aria-hidden="true"></i> Keys
				<span class="nav-count">{snapshot.profiles.length}</span></button
			><button class="header-button" onclick={() => (connectionOpen = true)}
				><i class="fa-solid fa-server" aria-hidden="true"></i> Connection</button
			><button class="icon-button" onclick={toggleMode} aria-label="Toggle light or dark theme"
				><i class="fa-solid fa-circle-half-stroke" aria-hidden="true"></i></button
			>
		</nav>
	</div>
</header>
<main>
	<div class="page-heading">
		<div>
			<p class="eyebrow">PHOTO MANAGEMENT</p>
			<h1>Photo catalogs</h1>
			<p class="muted">Bring Flickr albums and local photos to your presentations.</p>
		</div>
		{#if snapshot.configured}<button class="primary" onclick={() => (catalogEditor = {})}
				><i class="fa-solid fa-plus" aria-hidden="true"></i> New catalog</button
			>{/if}
	</div>
	{#if pollError}<div class="banner error" role="alert">
			<strong>Cannot reach the importer.</strong>
			{pollError} Existing photos remain on CDS. <button class="text-button" onclick={load}>Retry</button>
		</div>{/if}
	{#if snapshot.error}<div class="banner error" role="alert">
			<strong>Import needs attention.</strong>
			{snapshot.error} Available photos can still be used.
		</div>{/if}
	{#if actionError}<div class="banner error" role="alert">
			{actionError}<button class="icon-button" aria-label="Dismiss error" onclick={() => (actionError = '')}
				>×</button
			>
		</div>{/if}
	{#if notice}<div class="banner success" role="status">
			{notice}<button class="icon-button" aria-label="Dismiss notice" onclick={() => (notice = '')}>×</button>
		</div>{/if}
	{#if !loaded}<div class="empty-state">
			<i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
			<h2>Connecting to the importer…</h2>
			<p class="muted">{pollError || 'Loading local configuration.'}</p>
		</div>
	{:else if !snapshot.configured}
		<section class="empty-state welcome">
			<div class="welcome-icon"><i class="fa-regular fa-images" aria-hidden="true"></i></div>
			<h2>Your photos, ready for the floor</h2>
			<p>
				Connect to a CDS to manage catalogs and choose what this importer runs.<br />You can set up a Flickr
				key independently, or start with local files.
			</p>
			<div class="welcome-actions">
				<button class="primary" onclick={() => (connectionOpen = true)}
					>Connect to CDS <i class="fa-solid fa-arrow-right" aria-hidden="true"></i></button
				><button class="secondary" onclick={() => (credentialsOpen = true)}>Set up a Flickr key</button>
			</div>
		</section>
	{:else}
		<div class="connection-strip">
			<span class="connection-dot" class:unavailable={!!snapshot.error}></span><span
				>Target CDS <strong>{snapshot.connection.url}</strong></span
			><span class="connection-user">{snapshot.connection.user || 'Anonymous'}</span><span
				class="summary-count">{total} imported photos</span
			>
		</div>
		{#if !snapshot.catalogs.length}<section class="empty-state">
				<i class="fa-regular fa-folder-open" aria-hidden="true"></i>
				<h2>{snapshot.loading ? 'Discovering catalogs…' : 'Start with a catalog'}</h2>
				<p class="muted">
					A catalog groups the photos used by a presentation. Each can have one or more sources.
				</p>
				<button class="primary" onclick={() => (catalogEditor = {})}>Create catalog</button>
			</section>{/if}
		{#each snapshot.catalogs as catalog (catalog.id)}
			<section class="catalog-card" aria-label={`Catalog ${catalog.id}`}>
				<div class="catalog-heading">
					<div class="catalog-title">
						<i class="fa-regular fa-folder" aria-hidden="true"></i>
						<div>
							<h2>{catalog.id === 'photos' ? 'Default catalog' : catalog.id.slice(7)}</h2>
							<p>
								<code>{catalog.id === 'photos' ? 'catalog' : `catalog:${catalog.id.slice(7)}`}</code><span
									>Every {catalog.refreshSeconds} seconds</span
								>
							</p>
						</div>
					</div>
					<div class="catalog-actions">
						<button
							class="icon-button"
							aria-label={`Settings ${catalog.id}`}
							onclick={() => (catalogEditor = { catalog })}
							><i class="fa-solid fa-sliders" aria-hidden="true"></i></button
						><button class="secondary" onclick={() => (sourceEditor = { catalog: catalog.id })}
							><i class="fa-solid fa-plus" aria-hidden="true"></i> Add source</button
						>
					</div>
				</div>
				{#if !catalog.sources.length}<div class="catalog-empty">
						<p>No sources yet. Add a Flickr album or a local file/folder.</p>
						<p class="hint">
							Photos already placed on CDS remain available independently of importer sources.
						</p>
					</div>{/if}
				{#each catalog.sources as source (source.id)}
					{@const job = jobs.get(`${catalog.id}/${source.id}`)}
					<SourceRow
						{source}
						{job}
						catalog={catalog.id}
						busy={busy[`${catalog.id}/${source.id}`] || false}
						onaction={(name, body) => action(catalog.id, source, job, name, body)}
						onedit={() => (sourceEditor = { catalog: catalog.id, source, job })}
						onremove={() => removeSource(catalog.id, source)}
						loadresults={() =>
							api.request(`results/${encodeURIComponent(catalog.id)}/${encodeURIComponent(source.id)}`)}
					/>
				{/each}
			</section>
		{/each}
		<p class="page-note">
			<i class="fa-solid fa-circle-info" aria-hidden="true"></i> Imports run on this machine even after you close
			this page. New sources are not taken over automatically.
		</p>
	{/if}
	<footer>
		<span>ICPC tools · Photo importer</span>{#if snapshot.dataDirectory}<span
				class="data-directory"
				title={snapshot.dataDirectory}>Local data: {snapshot.dataDirectory}</span
			>{/if}
	</footer>
</main>
{#if connectionOpen}<ConnectionEditor
		connection={snapshot.connection}
		onclose={() => (connectionOpen = false)}
		onsave={async (input) => {
			await api.request('connection', 'PUT', input);
			await load();
			connectionOpen = false;
		}}
	/>{/if}
{#if credentialsOpen}<CredentialsEditor
		profiles={snapshot.profiles}
		onclose={() => (credentialsOpen = false)}
		onsave={async (name, key) => {
			await api.request(`credentials/${name.split('/').map(encodeURIComponent).join('/')}`, 'PUT', {
				apiKey: key
			});
			await load();
		}}
		onremove={async (name) => {
			await api.request(`credentials/${name.split('/').map(encodeURIComponent).join('/')}`, 'DELETE');
			await load();
		}}
	/>{/if}
{#if catalogEditor}<CatalogEditor
		catalog={catalogEditor.catalog}
		onclose={() => (catalogEditor = null)}
		onsave={async (id, refresh) => {
			if (!catalogEditor?.catalog && snapshot.catalogs.some((catalog) => catalog.id === id)) {
				throw new Error('That catalog already exists.');
			}
			await api.request(`catalogs/${encodeURIComponent(id)}`, 'PUT', { refreshSeconds: refresh });
			await load();
			catalogEditor = null;
		}}
	/>{/if}
{#if sourceEditor}<SourceEditor
		{...sourceEditor}
		catalogs={snapshot.catalogs}
		profiles={snapshot.profiles}
		onclose={() => (sourceEditor = null)}
		onsave={async (id, payload) => {
			await api.request(
				`catalogs/${encodeURIComponent(sourceEditor!.catalog)}/sources/${encodeURIComponent(id)}`,
				'PUT',
				payload
			);
			await load();
			sourceEditor = null;
		}}
	/>{/if}
