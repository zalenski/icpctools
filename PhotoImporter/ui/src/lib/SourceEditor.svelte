<script lang="ts">
	import { untrack } from 'svelte';
	import Dialog from './Dialog.svelte';
	import { sourcePayload, type Catalog, type Job, type SourceConfig, type SourceDraft } from './model';
	let {
		catalog,
		source,
		job,
		catalogs,
		profiles,
		onsave,
		onclose
	}: {
		catalog: string;
		source?: SourceConfig;
		job?: Job;
		catalogs: Catalog[];
		profiles: string[];
		onsave: (id: string, payload: ReturnType<typeof sourcePayload> & { create: boolean }) => Promise<void>;
		onclose: () => void;
	} = $props();
	let draft: SourceDraft = $state(
		untrack(() => ({
			id: source?.id || '',
			catalog,
			kind: source?.source.startsWith('file:') ? 'file' : 'flickr',
			uri: source?.source || '',
			profile: source?.credentialProfile || profiles[0] || '',
			tag: source?.tag || '',
			maxWidth: source?.maxWidth?.toString() || '',
			maxHeight: source?.maxHeight?.toString() || '',
			enabled: source?.enabled || false,
			runHere: job?.periodic || false,
			resumeOnRestart: job?.resumeOnRestart || false
		}))
	);
	let busy = $state(false);
	let error = $state('');
	async function save(event: SubmitEvent) {
		event.preventDefault();
		busy = true;
		error = '';
		try {
			await onsave(draft.id, { ...sourcePayload(draft), create: !source });
		} catch (e) {
			error = e instanceof Error ? e.message : 'Could not save source';
		} finally {
			busy = false;
		}
	}
</script>

<Dialog title={source ? 'Edit source' : 'Add source'} {onclose} {busy}>
	<form onsubmit={save}>
		<div class="form-grid">
			<label class="field"
				>Source ID<input bind:value={draft.id} disabled={!!source} required placeholder="team-photos" /><span
					class="hint">Stable name used for this import.</span
				></label
			>
			<label class="field"
				>Destination catalog<select bind:value={draft.catalog}
					>{#each catalogs as catalog (catalog.id)}<option value={catalog.id}
							>{catalog.id === 'photos' ? 'Default' : catalog.id.slice(7)}</option
						>{/each}</select
				></label
			>
		</div>
		{#if draft.catalog !== catalog}<p class="notice">
				Existing photos remain in the previous catalog. The source will import into the new destination.
			</p>{/if}
		<label class="field"
			>Source type<select bind:value={draft.kind}
				><option value="flickr">Flickr album</option><option value="file">Local file or folder</option
				></select
			></label
		>
		<label class="field"
			>{draft.kind === 'file' ? 'Path on the importer machine' : 'Flickr album URL'}<input
				bind:value={draft.uri}
				required
				placeholder={draft.kind === 'file'
					? '/path/to/photos or file:///path/to/photos/'
					: 'https://www.flickr.com/photos/icpc/albums/…'}
			/></label
		>
		{#if draft.kind === 'flickr'}
			<div class="form-grid">
				<label class="field"
					>Credential profile<select bind:value={draft.profile}
						><option value="">Select a profile</option>{#each profiles as profile (profile)}<option
								value={profile}>{profile}</option
							>{/each}{#if draft.profile && !profiles.includes(draft.profile)}<option value={draft.profile}
								>{draft.profile} (missing)</option
							>{/if}</select
					></label
				><label class="field"
					>Tag filter <span class="optional">optional</span><input
						bind:value={draft.tag}
						placeholder="event$Team Photos"
					/></label
				>
			</div>
			{#if !profiles.length}<p class="notice">
					Save a credential profile from the Keys button. You can save this source now and add the key later.
				</p>{/if}
		{:else}<p class="hint">
				The importer reads this path directly. No source credentials or browser file upload are needed.
			</p>{/if}
		<div class="form-grid">
			<label class="field"
				>Maximum width <span class="optional">pixels</span><input
					type="text"
					inputmode="numeric"
					bind:value={draft.maxWidth}
					placeholder="No limit"
				/></label
			><label class="field"
				>Maximum height <span class="optional">pixels</span><input
					type="text"
					inputmode="numeric"
					bind:value={draft.maxHeight}
					placeholder="No limit"
				/></label
			>
		</div>
		<p class="hint">Preserves proportions and never enlarges photos. Blank means no size limit.</p>
		<label class="check"
			><input type="checkbox" bind:checked={draft.enabled} /> Active — allow periodic imports</label
		>
		{#if draft.enabled}<label class="check"
				><input type="checkbox" bind:checked={draft.runHere} /> Run periodically on this importer</label
			>{/if}
		{#if draft.enabled && draft.runHere}<label class="check"
				><input type="checkbox" bind:checked={draft.resumeOnRestart} /> Resume automatically after this importer
				restarts</label
			>{/if}
		{#if error}<p class="error" role="alert">{error}</p>{/if}
		<div class="dialog-actions">
			<button class="secondary" type="button" disabled={busy} onclick={onclose}>Cancel</button><button
				class="primary"
				disabled={busy}>{busy ? 'Saving…' : 'Save source'}</button
			>
		</div>
	</form>
</Dialog>
