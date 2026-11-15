/**
 * A recipe's flow as a file. The format itself is the app's recipe file
 * (frontEnd/src/features/recipe-tool/model/recipeFlowFile.ts): what the bench writes can be imported in the
 * app, and what the editor exports can be benchmarked. Only reading it from disk is the bench's own.
 */

import { readFileSync } from 'node:fs'
import { parseFlowText, type FlowDoc } from '../../../frontEnd/src/features/recipe-tool/model/recipeFlowFile.ts'

export {
  FLOW_VERSION, VISUALIZATION_KEYS, parseFlowDoc, stripVisuals, type FlowDoc, type FlowProcess,
} from '../../../frontEnd/src/features/recipe-tool/model/recipeFlowFile.ts'

export function readFlowDoc(file: string): FlowDoc {
  let content: string
  try {
    content = readFileSync(file, 'utf8')
  } catch (error) {
    throw new Error(`flow file ${file}: cannot be read: ${(error as Error).message}`)
  }
  return parseFlowText(content, `flow file ${file}`)
}
