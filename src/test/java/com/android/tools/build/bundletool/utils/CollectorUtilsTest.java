/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.tools.build.bundletool.utils;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateFuture;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import java.util.stream.Stream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CollectorUtilsTest {

  @Test
  public void groupingBySortedKeys() {
    Multimap<Integer, String> map =
        Stream.of("this", "is", "a", "test", "for", "that", "method")
            .collect(CollectorUtils.groupingBySortedKeys(String::length));

    assertThat(map)
        .containsExactly(1, "a", 2, "is", 3, "for", 4, "this", 4, "test", 4, "that", 6, "method")
        .inOrder();
  }

  @Test
  public void groupingBySortedKeys_withValueFunction() {
    Multimap<Integer, String> map =
        Stream.of("this", "is", "a", "test", "for", "that", "method")
            .collect(CollectorUtils.groupingBySortedKeys(String::length, String::toUpperCase));

    assertThat(map)
        .containsExactly(1, "A", 2, "IS", 3, "FOR", 4, "THIS", 4, "TEST", 4, "THAT", 6, "METHOD")
        .inOrder();
  }

  @Test
  public void waitForAll() {
    ImmutableList<String> result =
        Stream.of(
                immediateFuture("this"),
                immediateFuture("is"),
                immediateFuture("a"),
                immediateFuture("test"))
            .collect(CollectorUtils.waitForAll());
    assertThat(result).containsExactly("this", "is", "a", "test").inOrder();
  }
}
