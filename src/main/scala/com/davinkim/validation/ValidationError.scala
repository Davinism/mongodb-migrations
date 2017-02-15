package com.davinkim.validation

/**
  * A validation error.
  *
  * @param messages the error message
  * @param args the error message arguments
  */
case class ValidationError(messages: Seq[String], args: Any*) {
  lazy val message = messages.last
}

object ValidationError {

  def apply(message: String, args: Any*) = new ValidationError(Seq(message), args: _*)

}