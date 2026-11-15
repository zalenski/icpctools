<script lang="ts">
	import { untrack } from 'svelte';
	import Dialog from './Dialog.svelte';
	import { catalogId, positive, type Catalog } from './model';
	let {
		catalog,
		onsave,
		onclose
	}: { catalog?: Catalog; onsave: (id: string, refresh: number) => Promise<void>; onclose: () => void } =
		$props();
	let name = $state(untrack(() => catalog?.id || ''));
	let refresh = $state(untrack(() => String(catalog?.refreshSeconds || 300)));
	let busy = $state(false);
	let error = $state('');
	async function save(event: SubmitEvent) {
		event.preventDefault();
		error = '';
		busy = true;
		try {
			const seconds = positive(refresh, 'Refresh interval');
			if (!seconds || seconds < 15) {
				throw new Error('Refresh must be at least 15 seconds.');
			}
			await onsave(catalogId(name), seconds);
		} catch (e) {
			error = e instanceof Error ? e.message : 'Could not save catalog';
		} finally {
			busy = false;
		}
	}
</script>

<Dialog title={catalog ? 'Catalog settings' : 'New catalog'} {onclose} {busy}>
	<form onsubmit={save}>
		<label class="field"
			>Catalog name<input
				bind:value={name}
				disabled={!!catalog}
				placeholder="teams (blank for the default catalog)"
			/></label
		>
		<label class="field"
			>Refresh interval <span class="optional">seconds</span><input
				bind:value={refresh}
				inputmode="numeric"
				required
			/></label
		>
		<p class="hint">Active sources in this catalog use this interval. Run now is available independently.</p>
		{#if error}<p class="error" role="alert">{error}</p>{/if}
		<div class="dialog-actions">
			<button class="secondary" type="button" disabled={busy} onclick={onclose}>Cancel</button><button
				class="primary"
				disabled={busy}>{busy ? 'Saving…' : 'Save catalog'}</button
			>
		</div>
	</form>
</Dialog>
