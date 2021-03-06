/*
 * Copyright (c) 2010-2013 Evolveum
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.evolveum.midpoint.prism.delta;

import com.evolveum.prism.xml.ns._public.types_3.ChangeTypeType;

/**
 * @author semancik
 *
 */
public enum ChangeType {
	ADD, MODIFY, DELETE;

	public static ChangeType toChangeType(ChangeTypeType changeType){

		if (changeType == null){
			return null;
		}

		switch (changeType){
		case ADD : return ChangeType.ADD;
		case DELETE : return ChangeType.DELETE;
		case MODIFY : return ChangeType.MODIFY;
		default : throw new IllegalArgumentException("Unknow change type: " + changeType);
		}
	}

	public static ChangeTypeType toChangeTypeType(ChangeType changeType){

		if (changeType == null) {
			return null;
		}

		switch (changeType) {
			case ADD : return ChangeTypeType.ADD;
			case DELETE : return ChangeTypeType.DELETE;
			case MODIFY : return ChangeTypeType.MODIFY;
			default : throw new IllegalArgumentException("Unknow change type: " + changeType);
		}
	}
}
