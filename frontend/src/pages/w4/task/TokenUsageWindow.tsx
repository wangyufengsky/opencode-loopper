/** Presenter only: the active domain owner owns monotonic totals and delta expiry. */
export function TokenUsageWindow({totalTokens,delta=0}:{totalTokens:number|null;delta?:number}) {
 return <div className="w4-token-window" role="status" aria-label="累计 Token"><span>{totalTokens===null?'用量待读取':`${totalTokens.toLocaleString()} Token`}</span>{delta>0&&<b className="w4-token-delta">+{delta.toLocaleString()}</b>}</div>
}
