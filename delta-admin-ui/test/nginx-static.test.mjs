import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'

const config = readFileSync(new URL('../nginx.conf', import.meta.url), 'utf8')

test('missing static assets return 404 instead of the SPA HTML fallback', () => {
  assert.match(config, /location \^~ \/assets\/ \{[\s\S]*?try_files \$uri =404;/)
  assert.match(config, /location \^~ \/static\/ \{[\s\S]*?try_files \$uri =404;/)
  assert.match(config, /location = \/instance-config\.js \{[\s\S]*?expires -1;/)
  assert.match(config, /location = \/index\.html \{[\s\S]*?expires -1;/)
})
