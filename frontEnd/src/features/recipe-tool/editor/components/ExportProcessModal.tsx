import { downloadTextFile, type ExportedRecipeFile } from '../../session/recipeFile'

const plural = (count: number, noun: string) => `${count} ${noun}${count === 1 ? '' : 's'}`

/** Shows the recipe's export file, with download and copy, and says what it holds and how it is imported. */
export default function ExportProcessModal({ file, onClose }: { file: ExportedRecipeFile; onClose: () => void }) {
  const { json, fileName, summary } = file
  const contents = [
    plural(summary.steps, 'step'),
    plural(summary.images, 'image'),
    plural(summary.audio, 'narration'),
  ].join(' · ')

  return (
    <div className="flow-canvas-export-modal-overlay" onClick={onClose}>
      <div className="flow-canvas-export-modal" role="dialog" aria-modal="true" aria-label="Export recipe" onClick={(e) => e.stopPropagation()}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, padding: '14px 18px', borderBottom: '1px solid var(--flow-border)' }}>
          <div>
            <div style={{ fontWeight: 700, fontSize: 14, color: 'var(--flow-text)' }}>📤 Export recipe</div>
            <div style={{ fontSize: 11, color: 'var(--flow-text-subtle)', marginTop: 2 }}>{fileName} · {contents}</div>
          </div>
          <div style={{ display: 'flex', gap: 7 }}>
            <button onClick={() => downloadTextFile(json, fileName)} style={{ padding: '6px 12px', borderRadius: 7, border: '1px solid var(--flow-success-border)', background: 'var(--flow-success-soft)', color: 'var(--flow-success)', fontSize: 12, fontWeight: 600, cursor: 'pointer' }}>
              ⬇ Download file
            </button>
            <button onClick={() => void navigator.clipboard.writeText(json)} style={{ padding: '6px 12px', borderRadius: 7, border: '1px solid var(--flow-info-border)', background: 'var(--flow-info-soft)', color: 'var(--flow-info)', fontSize: 12, fontWeight: 600, cursor: 'pointer' }}>
              📋 Copy
            </button>
            <button onClick={onClose} aria-label="Close" style={{ width: 30, height: 30, borderRadius: 7, border: '1px solid var(--flow-border)', background: 'var(--flow-surface)', color: 'var(--flow-text-subtle)', cursor: 'pointer', fontSize: 15 }}>✕</button>
          </div>
        </div>
        <p style={{ margin: 0, padding: '10px 18px', fontSize: 12, lineHeight: 1.5, color: 'var(--flow-text-muted)', borderBottom: '1px solid var(--flow-border)' }}>
          This file holds the whole recipe: its steps and details, with links to its images and narration.
          Anyone you share it with can create the recipe in their own account from <strong>New recipe → Import from file</strong>;
          they get their own copies of the images and narration.
        </p>
        <pre style={{ flex: 1, overflow: 'auto', margin: 0, padding: '14px 18px', fontSize: 11, lineHeight: 1.65, color: 'var(--flow-text)', background: 'var(--flow-surface-muted)', fontFamily: "'Fira Code', 'Cascadia Code', monospace" }}>
          {json}
        </pre>
      </div>
    </div>
  )
}
