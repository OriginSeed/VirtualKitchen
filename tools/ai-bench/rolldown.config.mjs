// Bundles the bench CLI together with the frontend modules it reuses (the generation converter and
// the editor's save path). Those modules transitively import Vite-only `import.meta.env`, which is
// defined as empty here: the bench never uses the frontend's own HTTP client.
export default {
  input: 'src/bench.ts',
  platform: 'node',
  transform: {
    define: { 'import.meta.env': '{}' },
  },
  output: { file: 'dist/bench.mjs', format: 'esm' },
}
