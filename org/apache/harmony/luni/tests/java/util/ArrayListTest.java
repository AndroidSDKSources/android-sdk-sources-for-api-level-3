package org.apache.harmony.luni.tests.java.util;

import dalvik.annotation.TestTargets;
import dalvik.annotation.TestLevel;
import dalvik.annotation.TestTargetNew;
import dalvik.annotation.TestTargetClass;

import junit.framework.TestCase;

import java.util.ArrayList;

@TestTargetClass(ArrayList.class) 
public class ArrayListTest extends TestCase {

    @TestTargetNew(
        level = TestLevel.COMPLETE,
        notes = "Regression test.",
        method = "add",
        args = {java.lang.Object.class}
    )
    public void test_addAllCollectionOfQextendsE() {
        // Regression for HARMONY-539
        // https://issues.apache.org/jira/browse/HARMONY-539
        ArrayList<String> alist = new ArrayList<String>();
        ArrayList<String> blist = new ArrayList<String>();
        alist.add("a");
        alist.add("b");
        blist.add("c");
        blist.add("d");
        blist.remove(0);
        blist.addAll(0, alist);
        assertEquals("a", blist.get(0));
        assertEquals("b", blist.get(1));
        assertEquals("d", blist.get(2));
    }

}