<script lang="ts">
	import { untrack } from 'svelte';
	import Dialog from './Dialog.svelte';
	import type { Snapshot } from './model';
	let {
		connection,
		onsave,
		onclose
	}: { connection: Snapshot['connection']; onsave: (value: unknown) => Promise<void>; onclose: () => void } =
		$props();
	let url = $state(untrack(() => connection.url || 'https://localhost:8443'));
	let user = $state(untrack(() => connection.user || 'admin'));
	let password = $state('');
	let insecureCds = $state(untrack(() => connection.insecureCds));
	let busy = $state(false);
	let error = $state('');
	async function save(event: SubmitEvent) {
		event.preventDefault();
		busy = true;
		error = '';
		try {
			const target = new URL(url);
			if (
				!['http:', 'https:'].includes(target.protocol) ||
				target.username ||
				target.password ||
				target.search ||
				target.hash
			) {
				throw new Error('Use the CDS root HTTP(S) URL, without credentials or query parameters.');
			}
			await onsave({ url: url.replace(/\/+$/, ''), user, password, insecureCds });
		} catch (e) {
			error = e instanceof Error ? e.message : 'Could not connect';
		} finally {
			busy = false;
		}
	}
</script>

<Dialog title="Connect to CDS" {onclose} {busy}>
	<form onsubmit={save}>
		<p class="muted">Connect this importer to the CDS that will receive your photos.</p>
		<label class="field"
			>CDS URL<input type="url" bind:value={url} required placeholder="https://cds.example.org" /></label
		>
		<div class="form-grid">
			<label class="field">Username<input autocomplete="username" bind:value={user} /></label>
			<label class="field"
				>Password<input
					type="password"
					autocomplete="current-password"
					bind:value={password}
					placeholder={connection.passwordConfigured ? 'Leave blank to keep saved password' : ''}
				/></label
			>
		</div>
		<label class="check"
			><input type="checkbox" bind:checked={insecureCds} /> Accept an untrusted CDS certificate</label
		>
		<p class="hint">Applies to this CDS connection only. Flickr certificates are still verified.</p>
		{#if error}<p class="error" role="alert">{error}</p>{/if}
		<div class="dialog-actions">
			<button class="secondary" type="button" disabled={busy} onclick={onclose}>Cancel</button><button
				class="primary"
				disabled={busy}>{busy ? 'Connecting…' : 'Connect'}</button
			>
		</div>
	</form>
</Dialog>
