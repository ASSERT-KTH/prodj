package se.kth.castor.rockstofetch.serialization;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

public class Json {

  private final ObjectMapper objectMapper = new ObjectMapper();

  public <T> T fromJson(String input, Class<T> clazz) throws IOException {
    return objectMapper.readValue(input, clazz);
  }

  public String toJson(Object value) throws IOException {
    return objectMapper.writeValueAsString(value);
  }

  public String prettyPrint(Object value) throws IOException {
    return objectMapper
        .writer(
            new DefaultPrettyPrinter().withArrayIndenter(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE)
        )
        .writeValueAsString(value);
  }

}
