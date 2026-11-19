<script lang="ts">
	import type { Snippet } from 'svelte';
	let {
		title,
		onclose,
		children,
		busy = false
	}: { title: string; onclose: () => void; children: Snippet; busy?: boolean } = $props();
	let dialog: HTMLDialogElement;
	$effect(() => {
		dialog?.showModal();
	});
</script>

<dialog
	bind:this={dialog}
	aria-label={title}
	{onclose}
	oncancel={(event) => {
		if (busy) {
			event.preventDefault();
		} else {
			onclose();
		}
	}}
>
	<div class="dialog-heading">
		<h2>{title}</h2>
		<button class="icon-button" type="button" aria-label="Close" disabled={busy} onclick={onclose}
			><i class="fa-solid fa-xmark" aria-hidden="true"></i></button
		>
	</div>
	{@render children()}
</dialog>
