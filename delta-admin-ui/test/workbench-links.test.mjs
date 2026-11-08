import test from 'node:test'
import assert from 'node:assert/strict'
import {taskLocation, taskPaths} from '../src/utils/workbench-links.js'

test('fulfillment tasks keep their type and task id in the route query', () => {
  assert.equal(taskPaths.RELAY, '/fulfillment-review')
  assert.equal(taskPaths.REPLACE, '/fulfillment-review')
  assert.deepEqual(taskLocation({kind: 'RELAY', id: 'r-1'}), {
    path: '/fulfillment-review', query: {type: 'relay', taskId: 'r-1'}
  })
  assert.deepEqual(taskLocation({kind: 'REPLACE', id: 'p-2'}), {
    path: '/fulfillment-review', query: {type: 'replace', taskId: 'p-2'}
  })
})
