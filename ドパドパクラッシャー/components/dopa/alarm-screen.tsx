'use client'

import { useState } from 'react'
import { BellOff } from 'lucide-react'
import { Marquee, Sticker, Watcher } from './primitives'

const snoozeComebacks = ['スヌーズ？ないよ。', 'だからないって。', '押しても無駄。', 'ベッドから出ろ。']

export function AlarmScreen({ onWake }: { onWake: () => void }) {
  const [snoozeCount, setSnoozeCount] = useState(0)

  return (
    <div className="relative flex h-full flex-col overflow-hidden bg-background">
      <div className="bg-hazard h-2 shrink-0" aria-hidden="true" />
      <Marquee text="起きろ WAKE UP 起きろ WAKE UP" className="bg-lime py-1.5 text-lime-foreground" />

      <div className="flex flex-1 flex-col items-center px-6 pt-6 text-center">
        <Sticker
          tone="alert"
          className="animate-blink text-3xl font-black px-6 py-3"
        >
          ● ALARM
        </Sticker>
        <p className="mt-3 font-display text-[92px] leading-none tracking-tight">07:30</p>
        <p className="mt-1 text-sm font-bold text-muted-foreground">木曜｜1限あるやつ</p>

        <div className="relative mt-4 w-full max-w-[280px]">
          <span className="absolute inset-0 m-auto size-48 animate-ring rounded-full border-2 border-lime" aria-hidden="true" />
          <Watcher mood="angry" className="relative aspect-square w-full animate-shake" priority />
          <p className="absolute -right-2 top-2 rotate-6 rounded-xl rounded-br-none bg-foreground px-3 py-2 font-display text-sm text-background shadow-[4px_4px_0_0_var(--lime)]">
            起きろ、
            <br />
            ドパガキ。
          </p>
        </div>

        <p className="mt-2 text-xs leading-relaxed text-muted-foreground">
          ベッドの圧力センサーが反応中。
          <br />
          ベッドから出るまで SNS とショート動画は封印されます。
        </p>
      </div>

      <div className="flex flex-col gap-3 px-5 pb-8">
        <button
          type="button"
          onClick={() => setSnoozeCount((c) => c + 1)}
          className="flex items-center justify-center gap-2 rounded-2xl border py-3 text-sm font-bold text-muted-foreground transition active:scale-[0.98]"
          aria-live="polite"
        >
          <BellOff className="size-4" aria-hidden="true" />
          {snoozeCount === 0 ? 'スヌーズ' : snoozeComebacks[(snoozeCount - 1) % snoozeComebacks.length]}
        </button>
        <button
          type="button"
          onClick={onWake}
          className="rounded-2xl bg-lime py-5 font-display text-xl text-lime-foreground shadow-[0_6px_0_0_#6f8f00] transition active:translate-y-1 active:shadow-[0_2px_0_0_#6f8f00]"
        >
          アラームを止める
        </button>
      </div>
    </div>
  )
}
