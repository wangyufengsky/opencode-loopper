/** Capture a plain DTO without serializing File or freezing browser/native objects. */
export function captureDto<T>(value: T): Readonly<T> {
  const visiting = new Set<object>()
  function copy(input: unknown): unknown {
    if (input === null || typeof input !== 'object') {
      if (typeof input === 'function' || typeof input === 'symbol' || typeof input === 'bigint') {
        throw new TypeError('操作输入必须是普通 DTO')
      }
      return input
    }
    if (visiting.has(input)) throw new TypeError('操作输入不能包含循环引用')
    if (!Array.isArray(input) && Object.getPrototypeOf(input) !== Object.prototype && Object.getPrototypeOf(input) !== null) {
      throw new TypeError('原生对象不能当作 DTO；File 必须独立保留原实例')
    }
    visiting.add(input)
    const output = Array.isArray(input) ? input.map(copy) : Object.fromEntries(
      Object.entries(input).map(([key, child]) => [key, copy(child)]),
    )
    visiting.delete(input)
    return Object.freeze(output)
  }
  return copy(value) as Readonly<T>
}
