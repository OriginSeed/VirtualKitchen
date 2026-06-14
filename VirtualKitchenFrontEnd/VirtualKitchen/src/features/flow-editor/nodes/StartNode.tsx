import { Handle, Position } from '@xyflow/react'

type Props = {
  data: {
    label: string
  }
}

export default function StartNode({ data }: Props) {
  return (
    <div
      className="
        bg-green-500
        text-white
        px-4
        py-2
        rounded-xl
        shadow-lg
        border-2
        border-green-700
        min-w-[150px]
        text-center
      "
    >
      <Handle
        type="target"
        position={Position.Top}
      />

      <div className="font-bold">
        {data.label}
      </div>

      <Handle
        type="source"
        position={Position.Bottom}
      />
    </div>
  )
}