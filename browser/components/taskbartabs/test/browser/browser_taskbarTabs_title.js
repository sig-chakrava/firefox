/* Any copyright is dedicated to the Public Domain.
 * http://creativecommons.org/publicdomain/zero/1.0/ */
"use strict";

ChromeUtils.defineESModuleGetters(this, {
  TaskbarTabs: "resource:///modules/taskbartabs/TaskbarTabs.sys.mjs",
  TaskbarTabsPin: "resource:///modules/taskbartabs/TaskbarTabsPin.sys.mjs",
  sinon: "resource://testing-common/Sinon.sys.mjs",
});

const kTaskbarTabName = "(Taskbar Tab Name)";
const kUserContextLabel = "(User Context Label)";
const kGenericProfileName = "(Profile Name)";
let gProfileName = null;

const kUri = Services.io.newURI("https://example.com");

sinon.stub(TaskbarTabsPin, "pinTaskbarTab");
sinon.stub(TaskbarTabsPin, "unpinTaskbarTab");

add_setup(function setup() {
  sinon
    .stub(ContextualIdentityService, "getUserContextLabel")
    .callsFake(index => {
      return index === 0 ? "" : kUserContextLabel;
    });
  sinon
    .stub(SelectableProfileService, "isEnabled")
    .get(() => gProfileName !== null);
  sinon.stub(SelectableProfileService, "currentProfile").get(() => ({
    name: gProfileName,
  }));
});

registerCleanupFunction(async function cleanup() {
  sinon.restore();
  await TaskbarTabs.resetForTests();
});

async function phaseBeforeContentTitle(aContainer, aProfileName) {
  gProfileName = aProfileName;

  // We intentionally delay getTaskbarTab to ensure that it works even if
  // getTaskbarTab takes a long time for one reason or another.
  const resolver = Promise.withResolvers();
  sinon.stub(TaskbarTabs, "getTaskbarTab").callsFake(async (...args) => {
    await resolver.promise;
    return TaskbarTabs.getTaskbarTab(...args);
  });

  const tt = await TaskbarTabs.findOrCreateTaskbarTab(
    Services.io.newURI("https://example.com/"),
    aContainer,
    {
      manifest: {
        name: kTaskbarTabName,
        start_url: "https://example.com/static/harness.css",
      },
    }
  );
  const win = await TaskbarTabs.openWindow(tt);
  await BrowserTestUtils.firstBrowserLoaded(win);

  TaskbarTabs.getTaskbarTab.restore();
  resolver.resolve();

  // It's hard to tell when the title is correct, since we can't rely on em
  // dashes (the default case without privacy doesn't have any!) nor can we
  // rely on the next change (the privacy cases stay the same!). Wait a
  // tick and hopefully that's enough.
  await TestUtils.waitForTick();

  return win;
}

async function phaseAfterContentTitle(aWindow) {
  const browser = aWindow.gBrowser.selectedBrowser;
  const promise = BrowserTestUtils.browserLoaded(browser);
  BrowserTestUtils.startLoadingURIString(browser, "https://example.com/");
  await promise;
}

function assertHasTaskbarTabName(aPrivate, aTitle) {
  is(
    aPrivate !== "privacy",
    aTitle.includes(kTaskbarTabName),
    `Does ${aPrivate === "privacy" ? "NOT " : ""}include Taskbar Tab name`
  );
}

function assertHasContainerName(aPrivate, aTitle) {
  is(
    aPrivate !== "privacy",
    aTitle.includes(kUserContextLabel),
    `Does ${aPrivate === "privacy" ? "NOT " : ""}include container name`
  );
}

async function test_defaultCase(aPrivate) {
  const win = await phaseBeforeContentTitle(0, null, aPrivate);
  const title = win.document.title;
  assertHasTaskbarTabName(aPrivate, title);
  ok(!title.includes(kUserContextLabel), "Doesn't include container name");

  await phaseAfterContentTitle(win);
  assertHasTaskbarTabName(aPrivate, title);
  ok(!title.includes(kUserContextLabel), "Title still has no container name");

  await BrowserTestUtils.closeWindow(win);
}

async function test_container(aPrivate) {
  const win = await phaseBeforeContentTitle(1, null, aPrivate);
  const title = win.document.title;
  assertHasTaskbarTabName(aPrivate, title);
  assertHasContainerName(aPrivate, title);

  await phaseAfterContentTitle(win);
  assertHasTaskbarTabName(aPrivate, title);
  assertHasContainerName(aPrivate, title);

  await BrowserTestUtils.closeWindow(win);
}

async function test_profile(aPrivate) {
  const win = await phaseBeforeContentTitle(0, kGenericProfileName, aPrivate);
  const title = win.document.title;
  assertHasTaskbarTabName(aPrivate, title);
  ok(!title.includes(kUserContextLabel), "Doesn't include container name");
  ok(title.includes(kGenericProfileName), "Does include profile name");

  await phaseAfterContentTitle(win);
  assertHasTaskbarTabName(aPrivate, title);
  ok(!title.includes(kUserContextLabel), "Title still has no container name");
  ok(title.includes(kGenericProfileName), "Does include profile name");

  await BrowserTestUtils.closeWindow(win);
}

async function test_profileAndContainer(aPrivate) {
  const win = await phaseBeforeContentTitle(1, kGenericProfileName, aPrivate);
  const title = win.document.title;
  assertHasTaskbarTabName(aPrivate, title);
  assertHasContainerName(aPrivate, title);
  ok(title.includes(kGenericProfileName), "Does include profile name");

  await phaseAfterContentTitle(win);
  assertHasTaskbarTabName(aPrivate, title);
  assertHasContainerName(aPrivate, title);
  ok(title.includes(kGenericProfileName), "Does include profile name");

  await BrowserTestUtils.closeWindow(win);
}

async function withoutExposingTitle(aTestCase) {
  await SpecialPowers.pushPrefEnv({
    set: [["privacy.exposeContentTitleInWindow", false]],
  });

  try {
    await aTestCase("privacy");
  } finally {
    await SpecialPowers.popPrefEnv();
  }
}

add_task(test_defaultCase);
add_task(test_container);
add_task(test_profile);
add_task(test_profileAndContainer);

add_task(async function test_defaultCase_privacy() {
  return withoutExposingTitle(test_defaultCase);
});

add_task(async function test_container_privacy() {
  return withoutExposingTitle(test_container);
});

add_task(async function test_profile_privacy() {
  return withoutExposingTitle(test_profile);
});

add_task(async function test_profileAndContainer_privacy() {
  return withoutExposingTitle(test_profileAndContainer);
});
