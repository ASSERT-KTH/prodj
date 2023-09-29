package se.kth.castor.rockstofetch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.Test;

public class ExampleTest {

  @Test
  void foo() {
    assertTrue(true);
  }

  @Test
  @SuppressWarnings("unchecked")
  void withMock() {
    List<String> list = mock(List.class);
    doReturn(true, false)
        .when(list)
        .add(any(String.class));

    assertTrue(list.add("Hello"));
    assertFalse(list.add("Hello"));
    assertFalse(list.add("Hello"));

    verify(list, atLeast(1)).add(any(String.class));
  }
}
