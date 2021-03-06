/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.unit_tests;

/**
 * 
 */
public class JavaPerformanceTests {

    public static String[] children() {
        return new String[] {
                StringTest.class.getName(),
                HashMapTest.class.getName(),
                ArrayListTest.class.getName(),
                TreeMapTest.class.getName(),
                TreeSetTest.class.getName(),
                HashSetTest.class.getName(),
                HashtableTest.class.getName(),
                VectorTest.class.getName(),
                LinkedListTest.class.getName(),
                MathTest.class.getName(),
        };
    }
}
