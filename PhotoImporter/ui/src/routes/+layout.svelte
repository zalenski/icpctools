<script lang="ts">
	import { ModeWatcher } from 'mode-watcher';
	import '@fortawesome/fontawesome-free/css/all.min.css';
	import '../app.css';
	let { children } = $props();
</script>

<ModeWatcher />
{@render children()}
