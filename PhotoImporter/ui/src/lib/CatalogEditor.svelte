<script lang="ts">
	import { untrack } from 'svelte';
	import Dialog from './Dialog.svelte';
	import { catalogId, type Catalog } from './model';
	let {
		catalog,
		onsave,
		onclose
	}: { catalog?: Catalog; onsave: (id: string) => Promise<void>; onclose: () => void } =
		$props();
	let name = $state(untrack(() => catalog?.id || ''));
	let busy = $state(false);
	let error = $state('');
	async function save(event: SubmitEvent) {
		event.preventDefault();
		error = '';
		busy = true;
		try {
			await onsave(catalogId(name));
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

		{#if error}<p class="error" role="alert">{error}</p>{/if}
		<div class="dialog-actions">
			<button class="secondary" type="button" disabled={busy} onclick={onclose}>Cancel</button><button
				class="primary"
				disabled={busy}>{busy ? 'Saving…' : 'Save catalog'}</button
			>
		</div>
	</form>
</Dialog>
