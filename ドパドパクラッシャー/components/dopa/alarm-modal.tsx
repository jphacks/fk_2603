"use client"

import { useState } from "react"

type AlarmModalProps = {
  onClose: () => void
  onSave: (time: string, days: number[]) => void
}

export function AlarmModal({ onClose, onSave }: AlarmModalProps) {
  const [time, setTime] = useState("07:30")

  return (
    <div className="fixed inset-0 z-[100] flex items-center justify-center bg-black/75 px-5">
      <div className="w-full max-w-[340px] rounded-2xl border-2 border-lime bg-surface p-5 text-foreground">
        <div className="flex items-center justify-between">
          <h2 className="font-display text-2xl">NEW ALARM</h2>

          <button type="button" onClick={onClose}>
            ✕
          </button>
        </div>

        <input
          type="time"
          value={time}
          onChange={(e) => setTime(e.target.value)}
          className="mt-6 w-full rounded-xl border bg-background p-4 text-center text-2xl"
        />

        <button
          type="button"
          onClick={() => onSave(time, [])}
          className="mt-6 w-full rounded-xl bg-lime p-4 font-bold text-lime-foreground"
        >
          この時間にセット！
        </button>
      </div>
    </div>
  )
}