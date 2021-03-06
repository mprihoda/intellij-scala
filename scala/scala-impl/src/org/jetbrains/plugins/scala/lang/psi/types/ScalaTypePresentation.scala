package org.jetbrains.plugins.scala
package lang
package psi
package types

import com.intellij.psi._
import org.jetbrains.plugins.scala.editor.documentationProvider.ScalaDocumentationProvider
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.parser.parsing.expressions.InfixExpr
import org.jetbrains.plugins.scala.lang.psi.api.base.ScFieldId
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.{ScBindingPattern, ScReferencePattern}
import org.jetbrains.plugins.scala.lang.psi.api.statements._
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.{ScParameter, ScTypeParam}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScTypedDefinition
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScObject, ScTypeDefinition}
import org.jetbrains.plugins.scala.lang.psi.types.api._
import org.jetbrains.plugins.scala.lang.psi.types.api.designator.{ScDesignatorType, ScProjectionType, ScThisType}
import org.jetbrains.plugins.scala.lang.psi.types.nonvalue.{ScMethodType, ScTypePolymorphicType}
import org.jetbrains.plugins.scala.lang.psi.types.recursiveUpdate.ScSubstitutor
import org.jetbrains.plugins.scala.lang.refactoring.util.{ScTypeUtil, ScalaNamesUtil}

import scala.annotation.tailrec

trait ScalaTypePresentation extends api.TypePresentation {
  typeSystem: api.TypeSystem =>

  import api.ScTypePresentation._

  protected override def typeText(`type`: ScType, nameFun: PsiNamedElement => String, nameWithPointFun: PsiNamedElement => String)
                                 (implicit context: TypePresentationContext): String = {
    def typesText(types: Seq[ScType]): String = types
      .map(innerTypeText(_))
      .commaSeparated(parenthesize = true)

    def typeTail(need: Boolean) = if (need) ".type" else ""

    def parametersText(parameters: Seq[ScTypeParam]): String = parameters match {
      case Seq() => ""
      case _ =>
        def typeParamText(param: ScTypeParam): String = {
          val substitutor = ScSubstitutor.empty

          def typeText0(tp: ScType) = typeText(substitutor.subst(tp), nameFun, nameWithPointFun)

          val buffer = new StringBuilder(if (param.isContravariant) "-" else if (param.isCovariant) "+" else "")
          buffer ++= param.name

          buffer ++= lowerBoundText(param.lowerBound)(typeText0)
          buffer ++= upperBoundText(param.upperBound)(typeText0)

          param.viewBound.foreach { tp =>
            buffer ++= " <% "
            buffer ++= typeText0(tp)
          }
          param.contextBound.foreach { tp =>
            buffer ++= " : "
            buffer ++= typeText0(ScTypeUtil.stripTypeArgs(substitutor.subst(tp)))
          }

          buffer.toString()
        }

        parameters.map(typeParamText).mkString("[", ", ", "]")
    }

    def projectionTypeText(projType: ScProjectionType, needDotType: Boolean): String = {
      val e = projType.actualElement
      val refName = e.name

      def checkIfStable(element: PsiElement): Boolean = element match {
        case _: ScObject | _: ScBindingPattern | _: ScParameter | _: ScFieldId => true
        case _ => false
      }

      val isStaticJavaClass = e match {
        case c: PsiClass => ScalaPsiUtil.isStaticJava(c)
        case _ => false
      }

      val typeTailForProjection = typeTail(checkIfStable(e) && needDotType)

      object StaticJavaClassHolder {
        def unapply(t: ScType): Option[PsiClass] = t match {
          case ScDesignatorType(clazz: PsiClass) => Some(clazz)
          case ParameterizedType(ScDesignatorType(clazz: PsiClass), _) => Some(clazz)
          case ScProjectionType(_, clazz: PsiClass) => Some(clazz)
          case _ => None
        }
      }

      if (context.nameResolvesTo(refName, e)) refName
      else
        projType.projected match {
          case ScDesignatorType(pack: PsiPackage) =>
            nameWithPointFun(pack) + refName
          case ScDesignatorType(named) if checkIfStable(named) =>
            nameWithPointFun(named) + refName + typeTailForProjection
          case ScThisType(obj: ScObject) =>
            nameWithPointFun(obj) + refName + typeTailForProjection
          case p@ScThisType(_: ScTypeDefinition) if checkIfStable(e) =>
            s"${innerTypeText(p, needDotType = false)}.$refName$typeTailForProjection"
          case p: ScProjectionType if checkIfStable(p.actualElement) =>
            s"${projectionTypeText(p, needDotType = false)}.$refName$typeTailForProjection"
          case StaticJavaClassHolder(clazz) if isStaticJavaClass =>
            nameWithPointFun(clazz) + refName
          case p@(_: ScCompoundType | _: ScExistentialType) =>
            s"(${innerTypeText(p)})#$refName"
          case p =>
            val innerText = innerTypeText(p)
            if (innerText.endsWith(".type")) innerText.stripSuffix("type") + refName
            else s"$innerText#$refName"
        }
    }

    def compoundTypeText(compType: ScCompoundType): String = {
      val ScCompoundType(comps, signatureMap, typeMap) = compType
      def typeText0(tp: ScType) = innerTypeText(tp)

      val componentsText = if (comps.isEmpty) Nil else Seq(comps.map {
        case tp@FunctionType(_, _) => "(" + innerTypeText(tp) + ")"
        case tp => innerTypeText(tp)
      }.mkString(" with "))

      val declsTexts = (signatureMap ++ typeMap).flatMap {
        case (s: Signature, rt: ScType) if s.namedElement.isInstanceOf[ScFunction] =>
          val fun = s.namedElement.asInstanceOf[ScFunction]
          val funCopy =
            ScFunction.getCompoundCopy(s.substitutedTypes.map(_.map(_()).toList), s.typeParams.toList, rt, fun)
          val paramClauses = ScalaDocumentationProvider.parseParameters(funCopy, -1)(typeText0)
          val retType = if (!compType.equiv(rt)) typeText0(rt) else "this.type"

          Seq(s"def ${s.name}${parametersText(funCopy.typeParameters)}$paramClauses: $retType")
        case (s: Signature, rt: ScType) if s.namedElement.isInstanceOf[ScTypedDefinition] =>
          if (s.paramLength.sum > 0) Seq.empty
          else {
            s.namedElement match {
              case bp: ScBindingPattern =>
                val b = ScBindingPattern.getCompoundCopy(rt, bp)
                Seq((if (b.isVar) "var " else "val ") + b.name + " : " + typeText0(rt))
              case fi: ScFieldId =>
                val f = ScFieldId.getCompoundCopy(rt, fi)
                Seq((if (f.isVar) "var " else "val ") + f.name + " : " + typeText0(rt))
              case _ => Seq.empty
            }
          }
        case (_: String, sign: TypeAliasSignature) =>
          val ta = ScTypeAlias.getCompoundCopy(sign, sign.ta)
          val defnText: String = ta match {
            case tad: ScTypeAliasDefinition =>
              tad.aliasedType.toOption
                .filterNot(_.isNothing)
                .map(typeText0)
                .map(" = " + _)
                .getOrElse("")
            case _ =>
              lowerBoundText(ta.lowerBound)(typeText0) +
                upperBoundText(ta.upperBound)(typeText0)
          }
          Seq(s"type ${ta.name}${parametersText(ta.typeParameters)}$defnText")
        case _ => Seq.empty
      }

      val refinementText = if (declsTexts.isEmpty) Nil else Seq(declsTexts.mkString("{\n  ", "\n\n  ", "\n}"))

      (componentsText ++ refinementText).mkString(" ")
    }

    @tailrec
    def existentialTypeText(existentialType: ScExistentialType, checkWildcard: Boolean, stable: Boolean): String = {
      def existentialArgWithBounds(wildcard: ScExistentialArgument, name: String): String = {
        val argsText =
          if (wildcard.typeParameters.isEmpty) ""
          else wildcard.typeParameters.map(_.name).mkString("[", ", ", "]")

        val lowerBound = lowerBoundText(wildcard.lower)(innerTypeText(_))
        val upperBound = upperBoundText(wildcard.upper)(innerTypeText(_))
        s"$name$argsText$lowerBound$upperBound"
      }

      def placeholder(wildcard: ScExistentialArgument) =
        existentialArgWithBounds(wildcard, "_")

      def namedExistentials(wildcards: Seq[ScExistentialArgument]) =
        wildcards.map { wildcard =>
          existentialArgWithBounds(wildcard, s"type ${wildcard.name}")
        }.mkString(" forSome {", "; ", "}")

      existentialType match {
        case ScExistentialType(q, Seq(w)) if checkWildcard =>
          if (q == w) placeholder(w)
          else existentialTypeText(existentialType, checkWildcard = false, stable)
        case ScExistentialType(quant @ ParameterizedType(des, typeArgs), wildcards) =>
          val usedMoreThanOnce = ScExistentialArgument.usedMoreThanOnce(quant)

          def mayBePlaceholder(arg: ScExistentialArgument): Boolean =
            !usedMoreThanOnce(arg) && typeArgs.contains(arg) && arg.typeParameters.isEmpty

          val (placeholders, namedWildcards) = wildcards.partition(mayBePlaceholder)

          val typeArgsText = typeArgs.map {
            case arg: ScExistentialArgument  => if (placeholders.contains(arg)) placeholder(arg) else arg.name
            case t                           => innerTypeText(t, needDotType = true, checkWildcard)
          }.mkString("[", ", ", "]")

          val prefix = s"${innerTypeText(des)}$typeArgsText"

          if (namedWildcards.isEmpty) prefix
          else s"($prefix)${namedExistentials(namedWildcards)}"
        case ex: ScExistentialType =>
          s"(${innerTypeText(ex.quantified)})${namedExistentials(ex.wildcards)}"
      }
    }
    
    object InfixDesignator {
      private[this] val showAsInfixAnnotation: String = "scala.annotation.showAsInfix"
      
      def unapply(des: ScType): Option[String] = {
        val aClass      = des.extractClass
        val annotations = aClass.toSeq.flatMap(_.getAnnotations)
        val text        = innerTypeText(des)
        val isQualified = text.contains(".")
        
        val isInfix = !isQualified &&
          (annotations.exists(_.getQualifiedName == showAsInfixAnnotation) ||
            ScalaNamesUtil.isOperatorName(text))
        
        if (isInfix) Some(text) else None
      }
    }

    object CanBeInfixType {
      def unapply(tpe: ScType): Option[(String, ScType, ScType, Int)] = tpe match {
        case ParameterizedType(InfixDesignator(text), Seq(lhs, rhs)) =>
          val assoc = InfixExpr.associate(text)
          Option((text, lhs, rhs, assoc))
        case _ => None
      }
    }

    def innerTypeText(t: ScType,
                      needDotType: Boolean = true,
                      checkWildcard: Boolean = false): String = t match {
      case namedType: NamedType => namedType.name
      case ScAbstractType(tpt, _, _) => tpt.name.capitalize + api.ScTypePresentation.ABSTRACT_TYPE_POSTFIX
      case FunctionType(ret, params) if t.isAliasType.isEmpty =>
        val paramsText = params match {
          case Seq(fun @ FunctionType(_, _)) => innerTypeText(fun).parenthesize()
          case Seq(head) => innerTypeText(head)
          case _ => typesText(params)
        }
        s"$paramsText ${ScalaPsiUtil.functionArrow} ${innerTypeText(ret)}"
      case ScThisType(element) =>
        val prefix = element match {
          case clazz: ScTypeDefinition => clazz.name + "."
          case _ => ""
        }

        prefix + "this" + typeTail(needDotType)
      case TupleType(comps) =>
        comps match {
          case Seq(head) => s"Tuple1[${innerTypeText(head)}]"
          case _ => typesText(comps)
        }
      case ScDesignatorType(element) =>
        val flag = element match {
          case _: ScObject | _: ScReferencePattern | _: ScParameter => true
          case _ => false
        }

        nameFun(element) + typeTail(flag && needDotType)
      case proj: ScProjectionType if proj != null =>
        projectionTypeText(proj, needDotType)
      case CanBeInfixType(op, lhs, rhs, assoc) =>
        def componentText(`type`: ScType, requiredAssoc: Int) = {
          val needParenthesis = `type` match {
            case CanBeInfixType(_, _, _, newAssoc) =>
              assoc != newAssoc || assoc == requiredAssoc
            case _ => false
          }

          innerTypeText(`type`).parenthesize(needParenthesis)
        }

        s"${componentText(lhs, -1)} $op ${componentText(rhs, 1)}"
      case ParameterizedType(des, typeArgs) =>
        innerTypeText(des) + typeArgs.map(innerTypeText(_, checkWildcard = true)).mkString("[", ", ", "]")
      case JavaArrayType(argument) => s"Array[${innerTypeText(argument)}]"
      case UndefinedType(tpt, _) => "NotInfered" + tpt.name
      case c: ScCompoundType if c != null =>
        compoundTypeText(c)
      case ex: ScExistentialType if ex != null =>
        existentialTypeText(ex, checkWildcard, needDotType)
      case ScTypePolymorphicType(internalType, typeParameters) =>
        typeParameters.map {
          case TypeParameter(parameter, _, lowerType, upperType) =>
            parameter.name + lowerBoundText(lowerType)(_.toString) + upperBoundText(upperType)(_.toString)
        }.mkString("[", ", ", "] ") + internalType.toString
      case mt@ScMethodType(retType, params, _) =>
        implicit val elementScope: ElementScope = mt.elementScope
        innerTypeText(FunctionType(retType, params.map(_.paramType)), needDotType)
      case lit: ScLiteralType => s"${lit.wideType.presentableText}(${Option(lit.literalValue).map(_.toString).orNull})"
      case _ => "" //todo
    }

    innerTypeText(`type`)
  }
}
