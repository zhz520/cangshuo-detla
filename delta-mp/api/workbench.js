import { get, post } from './request'
const quiet = { loading: false, silent: true }
export const getWorkbench = role => get('/workbench/summary', {}, { ...quiet, role, redirectOnUnauthorized: false })
export const getTasks = params => get('/workbench/tasks', params, quiet)
export const readTask = task => post('/workbench/read', [task], quiet)
export const acknowledgeTask = task => post('/workbench/acknowledge', task, quiet)
export const readAllTasks = () => post('/workbench/read-all', {}, quiet)
