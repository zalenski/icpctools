<script lang="ts">
	import { running, stateLabel, timestamp, type SourceConfig, type Job } from './model';
	import PhotoResults from './PhotoResults.svelte';
	let {
		source,
		job,
		catalog,
		busy = false,
		onaction,
		onedit,
		onremove,
		loadresults
	}: {
		source: SourceConfig;
		job?: Job;
		catalog: string;
		busy?: boolean;
		onaction: (action: string, body?: object) => Promise<void>;
		onedit: () => void;
		onremove: () => void;
		loadresults: () => Promise<unknown>;
	} = $props();
	let expanded = $state(false);
	let results = $state<{
		photos?: Record<string, unknown>[];
		local?: Record<string, unknown>[];
		failures?: { photoId: string; message: string }[];
	} | null>(null);
	let resultError = $state('');
	let resultLoading = $state(false);
	let pendingActivation: boolean | null = $state(null);
	let pendingRestart: boolean | null = $state(null);
	let active = $derived(running(job));
	let label = $derived(stateLabel(source, job));
	let resultVersion = $derived(`${job?.available}:${job?.lastSuccess}:${job?.progress.failed}`);
	$effect(() => {
		if (expanded) {
			void resultVersion;
			void refreshResults();
		}
	});
	async function toggle(action: 'activation' | 'restart', enabled: boolean) {
		if (action === 'activation') {
			pendingActivation = enabled;
		} else {
			pendingRestart = enabled;
		}
		try {
			await onaction(action, { enabled });
		} finally {
			if (action === 'activation') {
				pendingActivation = null;
			} else {
				pendingRestart = null;
			}
		}
	}

	async function refreshResults() {
		resultLoading = true;
		resultError = '';
		try {
			results = (await loadresults()) as typeof results;
		} catch (e) {
			resultError = e instanceof Error ? e.message : 'Could not load photo results';
		} finally {
			resultLoading = false;
		}
	}
</script>

<article class="source-row" data-source={source.id}>
	<div class="source-summary">
		<div class="source-title">
			<div class="source-icon">
				<i
					class={source.source.startsWith('file:') ? 'fa-solid fa-folder-open' : 'fa-brands fa-flickr'}
					aria-hidden="true"
				></i>
			</div>
			<div>
				<h3>{source.id}</h3>
				<p class="source-uri" title={source.source}>{source.source}</p>
			</div>
		</div>
		<div class="source-activation">
			<label class="switch-label"
				><input
					type="checkbox"
					class="switch"
					aria-label={`Active ${source.id}`}
					checked={pendingActivation ?? job?.enabled}
					disabled={busy}
					onchange={(event) => {
						void toggle('activation', event.currentTarget.checked);
					}}
				/><span>Active here</span></label
			>
		</div>
		<div class="source-state">
			<span
				class:working={active}
				class:problem={!!job?.error}
				class:inactive={!job?.enabled && !active}
				class="badge">{label}</span
			><small>{job?.periodic ? `Every ${job.refreshSeconds} seconds on this importer` : 'Not running periodically here'}</small>
		</div>
		<div class="source-count">
			<strong>{job && job.available >= 0 ? job.available : '—'}</strong><small>available</small>
		</div>
		<div class="source-actions">
			{#if active}<button
					class="secondary"
					disabled={busy || job?.state === 'STOPPING'}
					onclick={() => onaction('stop')}><i class="fa-solid fa-stop" aria-hidden="true"></i> Stop</button
				>
			{:else}<button class="secondary" disabled={busy} onclick={() => onaction('run')}
					><i class="fa-solid fa-play" aria-hidden="true"></i>
					{job?.state === 'FAILED' ? 'Retry' : 'Run now'}</button
				>{/if}
			<button class="icon-button" aria-label={`Edit ${source.id}`} onclick={onedit}
				><i class="fa-solid fa-pen" aria-hidden="true"></i></button
			>
			<button
				class="icon-button"
				aria-label={`Details ${source.id}`}
				aria-expanded={expanded}
				onclick={() => (expanded = !expanded)}
				><i class={expanded ? 'fa-solid fa-chevron-up' : 'fa-solid fa-chevron-down'} aria-hidden="true"
				></i></button
			>
		</div>
	</div>
	<div class="source-foot">
		<span class="compact-count">{job && job.available >= 0 ? job.available : '—'} available</span>
		<span>Last success <strong>{timestamp(job?.lastSuccess || 0)}</strong></span>
		<span
			>Next <strong
				>{job?.nextRun ? timestamp(job.nextRun) : active && job?.periodic ? 'After this run' : '—'}</strong
			></span
		>
		{#if active}<span class="progress-text"
				>{job?.progress.phase} · {job?.progress.processed || 0}/{job?.progress.total || '?'} processed · {job?.uploaded ||
					0} uploaded{#if job?.progress.photoId}
					· {job.progress.photoId}{/if}</span
			>{/if}
		{#if job?.progress.failed}<span class="danger-text"
				>{job.progress.failed} failed; available photos are kept</span
			>{/if}
	</div>
	{#if active && (job?.progress.total || 0) > 0}<progress
			max={job!.progress.total}
			value={job!.progress.processed}
			aria-label={`Progress ${source.id}`}
		></progress>{/if}
	{#if job?.error}<p class="row-error">
			<i class="fa-solid fa-circle-exclamation" aria-hidden="true"></i>
			{job.error}
		</p>{/if}
	{#if expanded}
		<div class="source-details">
			<div class="detail-controls">
				{#if job?.enabled && !job?.periodic && !active}<button
						class="secondary"
						disabled={busy}
						onclick={() => onaction('start', { resumeOnRestart: job?.resumeOnRestart || false })}
						>Run periodically here</button
					>{/if}
				{#if job?.periodic && !active}<button
						class="secondary"
						disabled={busy}
						onclick={() => onaction('stop')}>Stop periodic runs here</button
					>{/if}
				<label class="check"
					><input
						type="checkbox"
						checked={pendingRestart ?? job?.resumeOnRestart ?? false}
						disabled={busy || !job?.selected}
						onchange={(event) => {
							void toggle('restart', event.currentTarget.checked);
						}}
					/> Resume after importer restart</label
				>
				<button class="text-button danger-text" disabled={busy} onclick={onremove}>Remove source</button>
			</div>
			<p class="hint">
				Removing a source keeps its imported photos. Run now does not enable periodic imports.
			</p>
			{#if resultError}<p class="error">
					{resultError} <button class="text-button" onclick={refreshResults}>Retry</button>
				</p>{/if}
			{#if results}<PhotoResults data={results} />{:else if resultLoading}<p class="muted">
					Loading photo results…
				</p>{/if}
		</div>
	{/if}
</article>
