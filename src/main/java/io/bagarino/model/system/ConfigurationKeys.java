/**
 * This file is part of bagarino.
 *
 * bagarino is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * bagarino is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with bagarino.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.bagarino.model.system;

public final class ConfigurationKeys {

    public static final String INIT_COMPLETED = "INIT_COMPLETED";
    public static final String STRIPE_SECRET_KEY = "STRIPE_SECRET_KEY";
    public static final String STRIPE_PUBLIC_KEY = "STRIPE_PUBLIC_KEY";
    public static final String SPECIAL_PRICE_CODE_LENGTH = "SPECIAL_PRICE_CODE_LENGTH";
    public static final String MAX_AMOUNT_OF_TICKETS_BY_RESERVATION = "MAX_AMOUNT_OF_TICKETS_BY_RESERVATION";
    

    //smtp configuration related keys
    public static final String SMTP_HOST = "SMTP_HOST";
    public static final String SMTP_PORT = "SMTP_PORT";
    public static final String SMTP_PROTOCOL = "SMTP_PROTOCOL"; //smtp or smtps
    public static final String SMTP_USERNAME = "SMTP_USERNAME";
    public static final String SMTP_PASSWORD = "SMTP_PASSWORD";
    public static final String SMTP_FROM_EMAIL = "SMTP_FROM_EMAIL";
    public static final String SMTP_PROPERTIES = "SMTP_PROPERTIES";
    //

    private ConfigurationKeys() {
    }

}