import { LightningElement, createElement, toString, api } from 'lwc';
import * as testUtil from 'securemoduletest-test-util';
import * as simpleLib from 'securemoduletest-simple-lib';

export default class Bootstrap extends LightningElement {
    @api
    testWindowIsSecure() {
        testUtil.assertStartsWith("SecureWindow", window.toString(), "Expected window to"
         + " return SecureWindow in interop component");
        windowIsSecureInLocalFunc();
        const simpleCmp = this.template.querySelector("#securemoduletest-simple-cmp");
        return simpleCmp.testWindowIsSecure() && simpleLib.testWindowIsSecure();
    }

    @api
    testDollarAuraNotAccessibleInModules() {
        testUtil.assertEquals("undefined", typeof $A, "Expected $A to be not accessible in interop component"); // eslint-disable-line lwc/no-aura
        // modules can access $A through window and we are hoping to catch that while linting modules.
        // It was a conscious decision to not block $A on window for the sake of performance
        // testUtils.assertUndefined(window.$A);
        dollarAuraNotAccessibleInLocalFunc();
        const simpleCmp = this.template.querySelector("#securemoduletest-simple-cmp");
        return simpleCmp.testDollarAuraNotAccessibleInModules() && simpleLib.testDollarAuraNotAccessibleInModules();
    }

    @api
    testLWCIsSecure() {
        testUtil.assertStartsWith("SecureLWC", toString(), "Expected engine to return" +
            "SecureLWC in interop component");
        testUtil.assertDefined(LightningElement, "SecureLWC is preventing access to LightningElement in interop component");
        testUtil.assertUndefined(createElement, "SecureLWC is leaking properties in interop component");
        engineIsSecureInLocalFunc();
        const simpleCmp = this.template.querySelector("#securemoduletest-simple-cmp");
        return simpleCmp.testLWCIsSecure() && simpleLib.testLWCIsSecure();
    }

    @api
    testMiscGlobalsNotAccessibleInModules() {
        testUtil.assertEquals("undefined", typeof aura, "Expected 'aura' to be not accessible in interop component");
        testUtil.assertEquals("undefined", typeof sforce, "Expected 'sforce' to be not accessible in interop component");
        testUtil.assertEquals("undefined", typeof Sfdc, "Expected 'Sfdc' to be not accessible in interop component");
    }

    @api
    testSecureModulesInUnsupportedBrowsers() {
        testUtil.assertStartsWith("[object Window]", window.toString(), "Expected window to"
            + " return raw window in module for unsupported browsers");
        return true;
    }
}

function windowIsSecureInLocalFunc() {
    testUtil.assertStartsWith("SecureWindow", window.toString(), "Expected window to"
        + " return SecureWindow in local functions");
}

function engineIsSecureInLocalFunc() {
    testUtil.assertStartsWith("SecureLWC", toString(), "Expected engine to return" +
        "SecureLWC in local functions");
}

function dollarAuraNotAccessibleInLocalFunc() {
    testUtil.assertEquals("undefined", typeof $A, "Expected $A to be not accessible in local functions"); // eslint-disable-line lwc/no-aura
}