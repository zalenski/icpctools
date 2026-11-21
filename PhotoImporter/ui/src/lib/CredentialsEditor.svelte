<script lang="ts">
	import Dialog from './Dialog.svelte';
	let {
		profiles,
		onsave,
		onremove,
		onclose
	}: {
		profiles: string[];
		onsave: (name: string, key: string) => Promise<void>;
		onremove: (name: string) => Promise<void>;
		onclose: () => void;
	} = $props();
	let name = $state('default');
	let key = $state('');
	let busy = $state(false);
	let error = $state('');
	let saved = $state('');
	async function save(event: SubmitEvent) {
		event.preventDefault();
		error = '';
		saved = '';
		busy = true;
		try {
			const profile = name.startsWith('flickr/') ? name : `flickr/${name}`;
			if (!/^flickr\/[A-Za-z0-9_-]+$/.test(profile)) {
				throw new Error('Use letters, numbers, underscores and hyphens for the profile name.');
			}
			await onsave(profile, key.trim());
			key = '';
			saved = `Saved ${profile}.`;
		} catch (e) {
			error = e instanceof Error ? e.message : 'Could not save profile';
		} finally {
			busy = false;
		}
	}
	async function remove(profile: string) {
		if (
			!confirm(
				`Remove ${profile}? Sources that use it will need credentials. Existing photos stay available.`
			)
		) {
			return;
		}
		busy = true;
		error = '';
		saved = '';
		try {
			await onremove(profile);
		} catch (e) {
			error = e instanceof Error ? e.message : 'Could not remove profile';
		} finally {
			busy = false;
		}
	}
</script>

<Dialog title="Credential profiles" {onclose} {busy}>
	<p class="muted">
		Keys stay on this importer. Sources refer to a profile name; local files do not need one.
	</p>
	{#if profiles.length}<ul class="profile-list">
			{#each profiles as profile (profile)}<li>
					<span
						><i class="fa-solid fa-key" aria-hidden="true"></i> <code>{profile}</code>
						<span class="badge good">Configured</span></span
					><button class="text-button danger-text" disabled={busy} onclick={() => remove(profile)}
						>Remove</button
					>
				</li>{/each}
		</ul>{/if}
	<form onsubmit={save}>
		<h3>Add or replace a Flickr key</h3>
		<p class="hint">
			Create an API key at <a
				href="https://www.flickr.com/services/apps/create/"
				target="_blank"
				rel="noreferrer"
				>Flickr App Garden <i class="fa-solid fa-arrow-up-right-from-square" aria-hidden="true"></i></a
			>, then copy the API key here. Public album imports do not need the API secret.
		</p>
		<div class="form-grid">
			<label class="field">Profile name<input bind:value={name} required placeholder="default" /></label
			><label class="field"
				>Flickr API key<input type="password" bind:value={key} autocomplete="off" required /></label
			>
		</div>
		{#if error}<p class="error" role="alert">{error}</p>{/if}{#if saved}<p class="success" role="status">
				{saved}
			</p>{/if}
		<div class="dialog-actions">
			<button class="secondary" type="button" disabled={busy} onclick={onclose}>Close</button><button
				class="primary"
				disabled={busy}>{busy ? 'Saving…' : 'Save key'}</button
			>
		</div>
	</form>
</Dialog>
