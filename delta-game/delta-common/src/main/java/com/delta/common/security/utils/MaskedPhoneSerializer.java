package com.delta.common.security.utils;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
public class MaskedPhoneSerializer extends JsonSerializer<String>{
 @Override public void serialize(String value,JsonGenerator out,SerializerProvider provider)throws IOException{
  boolean privileged=false;try{privileged=java.util.Set.of("ADMIN","CS").contains(SecurityUtils.getUserType());}catch(Exception ignored){}
  out.writeString(privileged?value:value.replaceAll("(?<=^.{3}).(?=.{4})","*"));
 }
}
