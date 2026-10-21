import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { describe, it, after, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
const require = createRequire(import.meta.url);
const { initializeTestEnvironment, assertFails, assertSucceeds } = require('@firebase/rules-unit-testing');
const { doc, getDoc, setDoc, updateDoc, collection, getDocs, query, where, serverTimestamp } = require('firebase/firestore');
const rules = readFileSync(fileURLToPath(new URL('../firestore.rules', import.meta.url)), 'utf8');
const env = await initializeTestEnvironment({ projectId: 'demo-flowstate', firestore: { rules } });
const gid = 'AB12CD34EF56';
const group = (db, id = gid) => doc(db, 'groups', id);
const member = (db, uid) => doc(db, 'groups', gid, 'members', uid);
const alice = () => env.authenticatedContext('alice').firestore();
const bob = () => env.authenticatedContext('bob').firestore();
const payload = () => ({ name: 'Alice', photoUrl: '', weeklyFocusMs: 0, weekKey: '2026-W43', live: {}, updatedAt: serverTimestamp() });
after(() => env.cleanup());
beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async c => {
    await setDoc(group(c.firestore()), { name: 'Focus', memberIds: ['alice', 'bob'], createdAt: new Date() });
    await setDoc(group(c.firestore(), 'CD34EF56GH78'), { name: 'Other', memberIds: ['alice'], createdAt: new Date() });
  });
});
describe('Flowstate Firestore authorization', () => {
  it('denies unauthenticated group/member reads and writes', async () => {
    const db = env.unauthenticatedContext().firestore();
    await assertFails(getDoc(group(db)));
    await assertFails(setDoc(member(db, 'alice'), payload()));
    await assertFails(getDoc(member(db, 'alice')));
  });
  it('allows new group creation only with valid code and self-only membership', async () => {
    const db = alice();
    await assertSucceeds(setDoc(group(db, 'AA11BB22CC33'), { name: 'New', memberIds: ['alice'], createdAt: serverTimestamp() }));
    await assertFails(setDoc(group(db, 'bad-id'), { name: 'New', memberIds: ['alice'], createdAt: serverTimestamp() }));
    await assertFails(setDoc(group(db, 'AA11BB22CC44'), { name: 'New', memberIds: ['alice', 'bob'], createdAt: serverTimestamp() }));
  });
  it('allows a code-holder to read and add only their own membership', async () => {
    const db = env.authenticatedContext('charlie').firestore();
    await assertSucceeds(getDoc(group(db)));
    await assertSucceeds(updateDoc(group(db), { memberIds: ['alice', 'bob', 'charlie'] }));
    await assertFails(updateDoc(group(db), { memberIds: ['alice', 'bob', 'charlie', 'david'] }));
  });
  it('restricts group queries to membership of the querying user', async () => {
    const db = alice();
    const result = await assertSucceeds(getDocs(query(collection(db, 'groups'), where('memberIds', 'array-contains', 'alice'))));
    assert.equal(result.size, 2);
    await assertFails(getDocs(collection(db, 'groups')));
    await assertFails(getDocs(query(collection(db, 'groups'), where('memberIds', 'array-contains', 'bob'))));
  });
  it('prevents removing others, adding duplicates, or changing group fields', async () => {
    const db = alice();
    await assertFails(updateDoc(group(db), { memberIds: ['alice'] }));
    await assertFails(updateDoc(group(db), { memberIds: ['alice', 'bob', 'bob'] }));
    await assertFails(updateDoc(group(db), { name: 'Renamed' }));
    await assertFails(updateDoc(group(db), { createdAt: new Date() }));
  });
  it('allows member collection reads but only own member writes', async () => {
    const db = alice();
    await assertSucceeds(setDoc(member(db, 'alice'), payload()));
    await assertSucceeds(getDocs(collection(db, 'groups', gid, 'members')));
    await assertFails(setDoc(member(db, 'bob'), payload()));
    await assertFails(setDoc(member(bob(), 'alice'), payload()));
    const outsider = env.authenticatedContext('charlie').firestore();
    await assertFails(getDoc(member(outsider, 'alice')));
    await assertFails(getDocs(collection(outsider, 'groups', gid, 'members')));
  });
  it('allows self-leaving and denies former members access', async () => {
    const db = bob();
    await assertSucceeds(updateDoc(group(db), { memberIds: ['alice'] }));
    await assertFails(getDoc(member(db, 'alice')));
    await assertFails(setDoc(member(db, 'bob'), payload()));
  });
  it('keeps private account profiles scoped to their owner', async () => {
    await assertSucceeds(setDoc(doc(alice(), 'users', 'alice'), { name: 'Alice' }));
    await assertFails(getDoc(doc(bob(), 'users', 'alice')));
    await assertFails(setDoc(doc(bob(), 'users', 'alice'), { name: 'Bob' }));
  });
});
