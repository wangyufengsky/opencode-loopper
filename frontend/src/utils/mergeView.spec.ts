import { describe, expect, it } from 'vitest'
import { changedLineNumbers, countChangedGroups, languageForPath, parseMergeConflicts, resolveMergeConflict } from './mergeView'

describe('mergeView', () => {
  const conflicts = [
    'class Demo {',
    '<<<<<<< 源项目',
    '  void source() {}',
    '||||||| BASE',
    '  void base() {}',
    '=======',
    '  void task() {}',
    '>>>>>>> 任务',
    '}',
    '',
  ].join('\n')

  it('parses diff3 markers and resolves one block without leaving markers', () => {
    const regions = parseMergeConflicts(conflicts)
    expect(regions).toHaveLength(1)
    expect(regions[0]).toMatchObject({ startLine: 2, endLine: 8, sourceContent: '  void source() {}\n', taskContent: '  void task() {}\n' })
    expect(resolveMergeConflict(conflicts, 0, 'task')).toBe('class Demo {\n  void task() {}\n}\n')
  })

  it('marks changed target lines and counts contiguous groups', () => {
    const changed = changedLineNumbers('a\nb\nc\nd', 'a\nB\nc\nD')
    expect(changed).toEqual([2, 4])
    expect(countChangedGroups([2, 3, 7])).toBe(2)
  })

  it('selects Java and JSON language modes by path', () => {
    expect(languageForPath('src/main/Demo.java')).toBe('java')
    expect(languageForPath('data/config.JSON')).toBe('json')
    expect(languageForPath('README.md')).toBe('plain')
  })
})
