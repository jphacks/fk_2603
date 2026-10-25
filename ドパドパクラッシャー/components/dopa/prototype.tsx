'use client'

import { cn } from '@/lib/utils'
import { AppShell, flowSteps, useDopaApp } from './app-shell'
import { Sticker } from './primitives'

export function Prototype() {
  const app = useDopaApp()

  return (
    <div className="min-h-dvh bg-background bg-grid lg:flex lg:items-center lg:justify-center lg:gap-16 lg:px-10 lg:py-10">
      <aside className="hidden max-w-sm lg:block">
        <Sticker className="-rotate-2">UI PROTOTYPE v0.1</Sticker>
        <h1 className="mt-4 font-display text-5xl leading-[1.05]">
          ドパドパ
          <br />
          <span className="text-lime">クラッシャー</span>
        </h1>
        <p className="mt-4 text-sm leading-relaxed text-muted-foreground">
          M5Stack と圧力センサーでベッド在床を検知。ベッドにいる間は SNS とショート動画を封印し、
          ベッドから出たら解放する、大学生のための目覚ましアプリ。
        </p>

        <div className="mt-8">
          <p className="font-display text-xs tracking-widest text-muted-foreground">SCREENS</p>
          <ol className="mt-3 flex flex-col gap-1.5">
            {flowSteps.map((s, i) => {
              const active = app.screen === s.id
              return (
                <li key={s.id}>
                  <button
                    type="button"
                    onClick={() => app.setScreen(s.id)}
                    aria-current={active ? 'step' : undefined}
                    className={cn(
                      'flex w-full items-center gap-3 rounded-xl px-3 py-2 text-left text-sm font-bold transition',
                      active ? 'bg-lime text-lime-foreground' : 'text-muted-foreground hover:bg-surface hover:text-foreground',
                    )}
                  >
                    <span className="font-display text-xs">{String(i + 1).padStart(2, '0')}</span>
                    {s.label}
                  </button>
                </li>
              )
            })}
          </ol>
        </div>
        <p className="mt-6 text-xs text-muted-foreground">
          ホームの「アラームを鳴らす」から起床フロー全体を体験できます。
        </p>
      </aside>

      <div className="h-dvh w-full lg:h-[844px] lg:w-[390px] lg:shrink-0 lg:rounded-[56px] lg:border-[10px] lg:border-surface-2 lg:shadow-[0_0_0_2px_rgb(255_255_255_/_0.06),0_40px_120px_-20px_rgb(198_255_0_/_0.15)]">
        <div className="relative h-full overflow-hidden lg:rounded-[46px]">
          <div
            className="pointer-events-none absolute left-1/2 top-2.5 z-20 hidden h-7 w-28 -translate-x-1/2 rounded-full bg-black lg:block"
            aria-hidden="true"
          />
          <AppShell app={app} />
        </div>
      </div>
    </div>
  )
}
