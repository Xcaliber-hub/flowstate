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
const newAvatars = ['cat', 'owl', 'panda', 'penguin', 'cactus', 'octopus', 'mushroom', 'coffee', 'rocket', 'wave', 'snowflake', 'bolt'];
const allAvatars = ['sprout', 'sunbeam', 'moon', 'fox', 'orbit', 'summit', 'cloud', 'ember', 'turtle', 'jelly', 'comet', 'bloom', ...newAvatars];
const stats = { weeklySessions: 10, todayFocusMs: 4_320_000, dayKey: '2026-10-26' };
const payload = (avatarId) => ({ name: 'Alice', ...(avatarId === undefined ? {} : { avatarId }), photoUrl: '', weeklyFocusMs: 0, weekKey: '2026-W43', live: {}, updatedAt: serverTimestamp() });
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
  it('accepts only approved optional avatar IDs in owner profiles and group members', async () => {
    const db = alice();
    const profile = doc(db, 'users', 'alice');
    for (const avatarId of [...allAvatars, '']) {
      await assertSucceeds(setDoc(profile, { name: 'Alice', avatarId }));
      await assertSucceeds(setDoc(member(db, 'alice'), payload(avatarId)));
    }
    for (const avatarId of ['review-only', 'https://example.com/avatar.png', 'data:image/png;base64,AAAA', 42, null, true, {}, []]) {
      await assertFails(setDoc(profile, { name: 'Alice', avatarId }));
      await assertFails(setDoc(member(db, 'alice'), payload(avatarId)));
    }
    await assertFails(setDoc(member(db, 'alice'), { ...payload('fox'), photoUrl: 'https://example.com/avatar.png' }));
  });
  it('accepts legacy profiles and member docs without avatarId, and supports changing or resetting it', async () => {
    const db = alice();
    await assertSucceeds(setDoc(doc(db, 'users', 'alice'), { name: 'Alice' }));
    await assertSucceeds(setDoc(member(db, 'alice'), payload()));
    await assertSucceeds(setDoc(doc(db, 'users', 'alice'), { avatarId: 'orbit' }, { merge: true }));
    await assertSucceeds(setDoc(member(db, 'alice'), payload('orbit')));
    await assertSucceeds(setDoc(doc(db, 'users', 'alice'), { avatarId: '' }, { merge: true }));
    await assertSucceeds(setDoc(member(db, 'alice'), payload('')));
    await assertSucceeds(setDoc(doc(db, 'users', 'alice'), { name: 'Alice', avatarId: 'moon' }));
    await assertSucceeds(updateDoc(doc(db, 'users', 'alice'), { name: 'Alice Updated', avatarId: 'fox' }));
    const restoredProfile = await assertSucceeds(getDoc(doc(env.authenticatedContext('alice').firestore(), 'users', 'alice')));
    assert.equal(restoredProfile.data().avatarId, 'fox');
    await assertSucceeds(setDoc(member(db, 'alice'), payload('moon')));
    await assertSucceeds(updateDoc(member(db, 'alice'), { avatarId: 'summit', updatedAt: serverTimestamp() }));
    await assertSucceeds(updateDoc(member(db, 'alice'), { avatarId: '', updatedAt: serverTimestamp() }));
  });
  it('migrates an original avatar to every new preset and resets both documents', async () => {
    const db = alice();
    const profile = doc(db, 'users', 'alice');
    await assertSucceeds(setDoc(profile, { name: 'Alice', avatarId: 'sprout' }));
    await assertSucceeds(setDoc(member(db, 'alice'), payload('sprout')));
    for (const avatarId of ['cloud', 'ember', 'turtle', 'jelly', 'comet', 'bloom']) {
      await assertSucceeds(updateDoc(profile, { avatarId }));
      await assertSucceeds(updateDoc(member(db, 'alice'), { avatarId, updatedAt: serverTimestamp() }));
      assert.equal((await assertSucceeds(getDoc(profile))).data().avatarId, avatarId);
      assert.equal((await assertSucceeds(getDoc(member(db, 'alice')))).data().avatarId, avatarId);
    }
    await assertSucceeds(updateDoc(profile, { avatarId: '' }));
    await assertSucceeds(updateDoc(member(db, 'alice'), { avatarId: '', updatedAt: serverTimestamp() }));
    assert.equal((await assertSucceeds(getDoc(profile))).data().avatarId, '');
    assert.equal((await assertSucceeds(getDoc(member(db, 'alice')))).data().avatarId, '');
  });
  it('does not allow another user to modify a profile avatar', async () => {
    await assertFails(setDoc(doc(bob(), 'users', 'alice'), { name: 'Alice', avatarId: 'fox' }));
  });
  it('reproduces stale-field merge denial and repairs it with a canonical member snapshot', async () => {
    await env.withSecurityRulesDisabled(async c => {
      await setDoc(member(c.firestore(), 'alice'), {
        ...payload('summit'), obsoleteClientField: true, photoUrl: 'https://old.example/photo.png'
      });
    });
    const db = alice();
    await assertFails(setDoc(member(db, 'alice'), payload('cloud'), { merge: true }));
    await assertSucceeds(setDoc(member(db, 'alice'), payload('cloud')));
    const saved = (await getDoc(member(db, 'alice'))).data();
    assert.equal(saved.avatarId, 'cloud');
    assert.equal(saved.photoUrl, '');
    assert.equal('obsoleteClientField' in saved, false);
    await assertFails(setDoc(member(bob(), 'alice'), payload('ember')));
  });
  it('propagates all new presets across multiple groups and restores them in a fresh session', async () => {
    const db = alice();
    const profile = doc(db, 'users', 'alice');
    const otherMember = doc(db, 'groups', 'CD34EF56GH78', 'members', 'alice');
    for (const avatarId of ['cloud', 'ember', 'turtle', 'jelly', 'comet', 'bloom', 'fox', '']) {
      await assertSucceeds(setDoc(profile, { name: 'Alice', avatarId }, { merge: true }));
      await assertSucceeds(setDoc(member(db, 'alice'), payload(avatarId)));
      await assertSucceeds(setDoc(otherMember, payload(avatarId)));
      const fresh = env.authenticatedContext('alice').firestore();
      assert.equal((await getDoc(doc(fresh, 'users', 'alice'))).data().avatarId, avatarId);
      assert.equal((await getDoc(doc(fresh, 'groups', gid, 'members', 'alice'))).data().avatarId, avatarId);
      assert.equal((await getDoc(doc(fresh, 'groups', 'CD34EF56GH78', 'members', 'alice'))).data().avatarId, avatarId);
    }
  });
  it('rejects malformed names, negative totals, stale timestamps and arbitrary member fields', async () => {
    const db = alice();
    for (const changes of [{ name: '' }, { name: 'x'.repeat(41) }, { weeklyFocusMs: -1 },
      { updatedAt: new Date(0) }, { unexpected: true }]) {
      await assertFails(setDoc(member(db, 'alice'), { ...payload('bloom'), ...changes }));
    }
    await assertFails(setDoc(doc(db, 'users', 'alice'), { name: '', avatarId: 'bloom' }));
  });
  it('reproduces the six-work-six-fail symptom when the deployed rules are still old', async () => {
    // Keep only the original six presets in the avatar list.
    const oldRules = rules.replace(/'summit',[\s\S]*?\n(\s*)\]\);/, "'summit'\n$1]);");
    assert.notEqual(oldRules, rules);
    const oldEnv = await initializeTestEnvironment({ projectId: 'demo-flowstate-six-avatar', firestore: { rules: oldRules } });
    try {
      const profile = doc(oldEnv.authenticatedContext('alice').firestore(), 'users', 'alice');
      for (const avatarId of ['sprout', 'sunbeam', 'moon', 'fox', 'orbit', 'summit']) {
        await assertSucceeds(setDoc(profile, { name: 'Alice', avatarId }));
      }
      for (const avatarId of ['cloud', 'ember', 'turtle', 'jelly', 'comet', 'bloom']) {
        await assertFails(setDoc(profile, { name: 'Alice', avatarId }));
        assert.equal((await getDoc(profile)).data().avatarId, 'summit');
      }
    } finally { await oldEnv.cleanup(); }
  });
  it('accepts all 24 presets and rejects the 12 newest under the 1.1.2/1.1.3 rules', async () => {
    const db = alice();
    for (const avatarId of newAvatars) {
      await assertSucceeds(setDoc(doc(db, 'users', 'alice'), { name: 'Alice', avatarId }));
      await assertSucceeds(setDoc(member(db, 'alice'), { ...payload(avatarId), ...stats }));
    }
    const oldRules = rules.replace(/'bloom',[\s\S]*?\n(\s*)\]\);/, "'bloom'\n$1]);");
    assert.notEqual(oldRules, rules);
    const oldEnv = await initializeTestEnvironment({ projectId: 'demo-flowstate-twelve-avatar', firestore: { rules: oldRules } });
    try {
      const profile = doc(oldEnv.authenticatedContext('alice').firestore(), 'users', 'alice');
      await assertSucceeds(setDoc(profile, { name: 'Alice', avatarId: 'bloom' }));
      for (const avatarId of newAvatars) await assertFails(setDoc(profile, { name: 'Alice', avatarId }));
    } finally { await oldEnv.cleanup(); }
  });
  it('accepts optional member stats from 1.1.4 and the legacy payload without them', async () => {
    const db = alice();
    await assertSucceeds(setDoc(member(db, 'alice'), payload('fox')));
    await assertSucceeds(setDoc(member(db, 'alice'), { ...payload('fox'), ...stats }));
    await assertSucceeds(setDoc(member(db, 'alice'), { ...payload('fox'), weeklySessions: 0, todayFocusMs: 0, dayKey: '2026-01-01' }));
    const saved = (await assertSucceeds(getDoc(member(bob(), 'alice')))).data();
    assert.equal(saved.weeklySessions, 0);
    for (const changes of [{ weeklySessions: -1 }, { weeklySessions: 1.5 }, { weeklySessions: '3' }, { weeklySessions: 10001 },
      { todayFocusMs: -1 }, { todayFocusMs: 86_400_001 }, { todayFocusMs: '0' },
      { dayKey: 26 }, { dayKey: '26-10-2026' }, { dayKey: '2026-10-26T00' }]) {
      await assertFails(setDoc(member(db, 'alice'), { ...payload('fox'), ...stats, ...changes }));
    }
  });
  it('accepts running and paused live snapshots and rejects unknown timer states', async () => {
    const db = alice();
    const live = (status, extra = {}) => ({ mode: 'POMODORO', phase: 'FOCUS', status, phaseDurationMs: 1_500_000, phaseStartedAt: 1_000, pausedAt: 0, subject: '', ...extra });
    await assertSucceeds(setDoc(member(db, 'alice'), { ...payload('fox'), ...stats, live: live('RUNNING') }));
    await assertSucceeds(setDoc(member(db, 'alice'), { ...payload('fox'), ...stats, live: live('PAUSED', { pausedAt: 901_000 }) }));
    await assertSucceeds(setDoc(member(db, 'alice'), { ...payload('fox'), live: { ...live('IDLE'), phase: 'IDLE', mode: 'FLOW' } }));
    assert.equal((await assertSucceeds(getDoc(member(bob(), 'alice')))).data().live.mode, 'FLOW');
    for (const bad of [live('SLEEPING'), { ...live('RUNNING'), phase: 'NAP' }, live('PAUSED', { pausedAt: -5 }), live('PAUSED', { pausedAt: 'x' })]) {
      await assertFails(setDoc(member(db, 'alice'), { ...payload('fox'), ...stats, live: bad }));
    }
  });
});
