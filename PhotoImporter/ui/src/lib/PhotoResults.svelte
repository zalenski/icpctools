<script lang="ts">
	import { numeric } from './model';
	let {
		data
	}: {
		data: {
			photos?: Record<string, unknown>[];
			local?: Record<string, unknown>[];
			failures?: { photoId: string; message: string }[];
		};
	} = $props();
	let limit = $state(50);
	let rows = $derived.by(() => {
		const result = new Map<
			string,
			{ id: string; title: string; status: string; width: number; height: number }
		>();
		for (const photo of data.photos || []) {
			result.set(String(photo.photoId), {
				id: String(photo.photoId),
				title: String(photo.title || photo.photoId),
				status: 'Ready',
				width: numeric(photo.width),
				height: numeric(photo.height)
			});
		}
		for (const observation of data.local || []) {
			if (observation.result !== 'valid') {
				const id = String(observation.path);
				const previous = result.get(id);
				result.set(id, {
					id,
					title: previous?.title || id,
					status: previous
						? `Last good copy · source ${observation.result}`
						: observation.result === 'invalid'
							? 'Not a supported image'
							: 'Retry next scan',
					width: previous?.width || 0,
					height: previous?.height || 0
				});
			}
		}
		for (const failure of data.failures || []) {
			const previous = result.get(failure.photoId);
			result.set(failure.photoId, {
				id: failure.photoId,
				title: `${previous?.title || failure.photoId} — ${failure.message}`,
				status: previous ? 'Last good copy · retry needed' : 'Failed',
				width: previous?.width || 0,
				height: previous?.height || 0
			});
		}
		return [...result.values()];
	});
</script>

{#if !rows.length}<p class="muted">
		No imported photos yet. Completed photos appear here even while an import is still running.
	</p>
{:else}
	<div class="table-scroll">
		<table class="results-table">
			<thead><tr><th>Photo</th><th>Status</th><th>Display size</th></tr></thead><tbody>
				{#each rows.slice(0, limit) as row (row.id)}<tr
						><td
							><span>{row.title}</span>{#if row.title !== row.id}<small>{row.id}</small>{/if}</td
						><td>{row.status}</td><td>{row.width ? `${row.width} × ${row.height}` : '—'}</td></tr
					>{/each}
			</tbody>
		</table>
	</div>
	{#if rows.length > limit}<button class="text-button" onclick={() => (limit += 50)}
			>Show more ({rows.length - limit} remaining)</button
		>{/if}
{/if}
