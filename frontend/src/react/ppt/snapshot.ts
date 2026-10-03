/** PPT DTOs contain JSON data only; reading them here also tracks nested Vue changes. */
export function pptDtoSnapshot<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}
